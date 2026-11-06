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

    /**
     * A request for photos ("去年の夏の花火の写真") is answered with what the search finds, with no model; null for a
     * question. Right after photos were found, conditions alone ("一昨年は？", "動画は？") change that search.
     */
    suspend fun find(question: String, earlier: Found? = null): Found? {
        val read = QueryReader.read(question, now())
        val wanted = follow(question, read, earlier?.read) ?: read.takeIf { QueryReader.asksForMedia(question) } ?: return null
        return Found(wanted, lookUp(wanted))
    }

    suspend fun answer(question: String, earlier: List<Turn>, onText: (String) -> Unit = {}): AskResult {
        val found = lookUp(QueryReader.read(question, now()))
        val chat = earlier.map { it.question to it.answer.text.replace(CITATION, "") }
        return ask.ask(question, sources(found, earlier), AskLibrary.context(null, null, chat), onText)
    }

    private suspend fun lookUp(read: ReadQuery): List<SearchHit> =
        search(read.rest, kind = read.kind, filters = SearchFilters(read.fromMs, read.untilMs), onlyAtPlace = true).hits

    internal companion object {
        /** Sources the last answer cited and still kept for a follow-up; the rest are the new search's. */
        const val KEPT = 2

        // The numbers in an earlier answer point at that answer's sources, not this one's.
        private val CITATION = Regex("""\s*\[\d+]""")

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
