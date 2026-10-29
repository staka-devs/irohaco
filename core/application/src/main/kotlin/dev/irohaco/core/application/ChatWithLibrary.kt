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

    fun warmUp() = ask.warmUp()

    suspend fun answer(question: String, earlier: List<Turn>, onText: (String) -> Unit = {}): AskResult {
        val read = QueryReader.read(question, now())
        val found = search(read.rest, kind = read.kind, filters = SearchFilters(read.fromMs, read.untilMs), onlyAtPlace = true).hits
        val chat = earlier.map { it.question to it.answer.text.replace(CITATION, "") }
        return ask.ask(question, sources(found, earlier), AskLibrary.context(null, null, chat), onText)
    }

    internal companion object {
        /** Sources the last answer cited and still kept for a follow-up; the rest are the new search's. */
        const val KEPT = 2

        // The numbers in an earlier answer point at that answer's sources, not this one's.
        private val CITATION = Regex("""\s*\[\d+]""")

        fun sources(found: List<SearchHit>, earlier: List<Turn>): List<SearchHit> =
            (earlier.lastOrNull()?.answer?.citations.orEmpty().map { it.hit }.take(KEPT) + found)
                .distinctBy { it.item.assetId to it.timestampMs }
                .take(AskLibrary.MAX_SOURCES)
    }
}
