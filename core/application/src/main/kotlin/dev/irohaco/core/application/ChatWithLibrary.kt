package dev.irohaco.core.application

import java.time.ZonedDateTime

/**
 * Free questions about the library (#202): each question is read for its dates, kind and place, searched
 * like the search screen does, and answered from the top results with the local model. The search already
 * leaves out files the app may not read now and folders hidden from the timeline (ADR-0009, GAL-002).
 * A follow-up also gets the sources the last answer cited, so the model can build on them or use the new ones.
 */
class ChatWithLibrary(
    private val search: SearchMedia,
    private val ask: AskLibrary,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) {
    data class Turn(val question: String, val answer: AskResult.Answered)

    /** Photos found for a request, and the conditions they were searched with, shown beside them. */
    data class Found(val read: ReadQuery, val hits: List<SearchHit>)

    fun warmUp() = ask.warmUp()

    /** A question read as a search, and whether it asks to see photos ([show]) rather than to be answered. */
    data class Reading(val read: ReadQuery, val show: Boolean)

    /**
     * Reads [question] with the answering model, in any language, so the search gets fitting words instead of the
     * question's own ("去年の夏はどこに行った？" searches for trips in last summer). Right after photos were found, the
     * model gets that search too, so "一昨年は？" or "動画は？" changes it. Without a model the fixed rules read it.
     */
    suspend fun read(question: String, earlier: Found? = null): Reading {
        val rules = QueryReader.read(question, now())
        val plan = ask.plan(question, now(), earlier?.read)
            ?: return follow(question, rules, earlier?.read)?.let { Reading(it, show = true) } ?: Reading(rules, QueryReader.asksForMedia(question))
        return Reading(merge(rules, plan), plan.show)
    }

    /** A request for photos ("去年の夏の花火の写真") is answered with what the search finds, with no model. */
    suspend fun find(read: ReadQuery): Found = lookUpAt(read).let { (resolved, results) -> Found(resolved, results.hits) }

    suspend fun answer(question: String, read: ReadQuery, earlier: List<Turn>, onText: (AskPartial) -> Unit = {}): AskResult {
        val found = lookUp(read)
        val chat = earlier.map { it.question to it.answer.text.replace(CITATION, "") }
        return ask.ask(question, sources(found, earlier), AskLibrary.context(null, null, chat), onText)
    }

    private suspend fun lookUp(read: ReadQuery): List<SearchHit> = lookUpAt(read).second.hits

    /**
     * The search a reading asks for, and the reading with its place as the library names it. The place, the model's or
     * one the words name, narrows the files to those taken there, and the words without it are looked for by meaning:
     * "大阪の花火" finds fireworks among the photos of 大阪府. A place no photo was taken at stays among the words.
     */
    private suspend fun lookUpAt(read: ReadQuery): Pair<ReadQuery, SearchResults> {
        val asked = read.place?.let { search.placeNamed(it) }
        val place = asked ?: if (read.place == null) search.placeNamed(read.rest) else null
        val text = if (read.place != null && asked == null) "${read.place} ${read.rest}".trim() else read.rest
        val words = place?.let { SearchPlaces.without(text, it.trimEnd(*SearchPlaces.SUFFIXES)) }?.takeIf { it.isNotBlank() } ?: text
        val resolved = read.copy(rest = words, place = place)
        return resolved to search(words, kind = read.kind, filters = SearchFilters(read.fromMs, read.untilMs, place = place))
    }

    internal companion object {
        /** Sources the last answer cited and still kept for a follow-up; the rest are the new search's. */
        const val KEPT = 2

        // The numbers in an earlier answer point at that answer's sources, not this one's.
        private val CITATION = Regex("""\s*\[\d+]""")

        /** The model's reading; only when it gave no words does the search fall back on the rules' words. */
        fun merge(rules: ReadQuery, plan: SearchPlan): ReadQuery {
            val words = plan.words.takeIf { it.isNotBlank() }
            return ReadQuery(words ?: rules.rest, plan.fromMs, plan.untilMs, plan.kind, hasWords = words != null || rules.hasWords, place = plan.place)
        }

        /** The last search with the dates or kind [read] names put in its place; null unless [question] is only that. */
        fun follow(question: String, read: ReadQuery, last: ReadQuery?): ReadQuery? {
            if (last == null || read.hasWords || (read.fromMs == null && read.kind == null) || QueryReader.isQuestion(question)) return null
            return last.copy(
                fromMs = read.fromMs ?: last.fromMs,
                untilMs = if (read.fromMs != null) read.untilMs else last.untilMs,
                kind = read.kind ?: last.kind,
            )
        }

        fun sources(found: List<SearchHit>, earlier: List<Turn>): List<SearchHit> =
            (earlier.lastOrNull()?.answer?.citations.orEmpty().map { it.hit }.take(KEPT) + found)
                .distinctBy { it.item.assetId to it.timestampMs }
                .take(AskLibrary.MAX_SOURCES)
    }
}
