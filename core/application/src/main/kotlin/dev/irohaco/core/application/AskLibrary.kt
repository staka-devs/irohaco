package dev.irohaco.core.application

import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AnswerModelSettings
import dev.irohaco.core.contracts.AnswerRuntime
import dev.irohaco.core.contracts.AnswerSession
import dev.irohaco.core.contracts.IndexImageReader
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Where a cited fact comes from (RAG-003): seen in the image, written by the user, or guessed. */
enum class Basis { OBSERVED, USER_STATED, INFERRED }

/**
 * A search result the answer rests on, tapped to open the photo or the video at its scene (RAG-002).
 * [number] is how the answer's text cites it, as in "[2]".
 */
data class Citation(val hit: SearchHit, val basis: Basis, val number: Int)

sealed interface AskResult {
    /** [known] is false when the sources do not answer the question; the text then says so (RAG-003). */
    data class Answered(val text: String, val known: Boolean, val citations: List<Citation>) : AskResult

    /** No answer model is installed; Settings > AI downloads one. */
    data object NoModel : AskResult

    /** Nothing was found to answer from. */
    data object NothingFound : AskResult

    data class Failed(val cause: Throwable) : AskResult
}

/**
 * Answers a question from the top search results on this device (RAG-001〜003, ARCHITECTURE §7.2). The
 * model sees the actual photos and video frames, the user's memos and the text read in them, and cites
 * each one it uses as seen, stated by the user, or guessed. The search already left out what the app may
 * not read now (ADR-0009). Loading takes a model 25 to 45 s, so a screen that may ask warms it up first,
 * and it stays loaded until it sits unused for five minutes.
 */
class AskLibrary(
    private val installed: (id: String) -> String?,
    private val settings: AnswerModelSettings,
    private val runtime: AnswerRuntime,
    private val images: IndexImageReader,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val backend: suspend () -> AiBackend,
    val catalog: List<ModelArtifact> = ModelCatalog.ANSWER,
    private val idleMs: Long = IDLE_MS,
) {
    val chosen: Flow<String?> = settings.answerModel

    suspend fun choose(id: String) {
        settings.setAnswerModel(id)
        release()
    }

    private val lock = Mutex()
    private var session: AnswerSession? = null
    private var loadedPath: String? = null
    private var idleRelease: Job? = null

    /** The picked model when it is installed, else the first installed one in the catalog's order. */
    suspend fun modelPath(): String? {
        val picked = settings.answerModel.first()?.let(installed)
        return picked ?: catalog.firstNotNullOfOrNull { installed(it.id) }
    }

    /** Loads the model in the background, so a question asked soon after does not wait for it. */
    fun warmUp() {
        scope.launch {
            val path = modelPath() ?: return@launch
            try {
                withSession(path) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The question that follows reports it.
            }
        }
    }

    /**
     * Asks about the photo or the video's scene open in the viewer (#201). The model also gets when and where
     * it was taken, the user's memos on it, and the last few questions and answers, so a follow-up can build on them.
     */
    suspend fun askAbout(
        question: String,
        item: TimelineItem,
        frameMs: Long?,
        memos: List<String>,
        takenAtMs: Long?,
        place: String?,
        earlier: List<Pair<String, String>>,
        onText: (String) -> Unit = {},
    ): AskResult {
        val memo = memos.filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { null }
        val hit = SearchHit(item, frameMs ?: 0L.takeIf { item.kind == MediaKind.VIDEO }, setOfNotNull(Evidence.MEMO.takeIf { memo != null }), memo)
        return ask(question, listOf(hit), context(takenAtMs, place, earlier), onText)
    }

    /** [onText] gets the answer's text so far while the model writes it; [context] goes just before the question. */
    suspend fun ask(question: String, hits: List<SearchHit>, context: String = "", onText: (String) -> Unit = {}): AskResult {
        val path = modelPath() ?: return AskResult.NoModel
        val sources = hits.take(MAX_SOURCES)
        if (question.isBlank() || sources.isEmpty()) return AskResult.NothingFound
        return try {
            val pictures = withContext(io) {
                sources.map { hit -> hit.timestampMs?.let { images.readFrame(hit.item.contentUri, it) } ?: images.read(hit.item.contentUri) }
            }
            val reply = withSession(path) { session ->
                session.answer(INSTRUCTION, pictures, prompt(question, sources, context)) { onText(visibleAnswer(it)) }
            }
            parseAnswer(reply, sources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) { // a native library that fails to load is an Error, not an Exception
            AskResult.Failed(e)
        }
    }

    suspend fun release() {
        lock.withLock {
            session?.let { withContext(io) { it.close() } }
            session = null
            loadedPath = null
        }
    }

    /** Runs [block] on the model at [path], loading it first if another one or none is loaded. */
    private suspend fun <T> withSession(path: String, block: suspend (AnswerSession) -> T): T = lock.withLock {
        idleRelease?.cancel()
        try {
            val loaded = session.takeIf { loadedPath == path } ?: load(path)
            withContext(io) { block(loaded) }
        } finally {
            idleRelease = scope.launch {
                delay(idleMs)
                release()
            }
        }
    }

    /** Called with [lock] held. */
    private suspend fun load(path: String): AnswerSession {
        session?.let { withContext(io) { it.close() } }
        session = null
        val chosenBackend = backend()
        return withContext(io) { runtime.load(path, chosenBackend).also { it.warm() } }.also {
            session = it
            loadedPath = path
        }
    }

    internal companion object {
        const val IDLE_MS = 5 * 60_000L

        /** Each image costs a model about 256 tokens; the smallest context here is 2,048. */
        const val MAX_SOURCES = 4

        const val INSTRUCTION =
            """You answer questions about the user's own photos and videos. You are given numbered sources: one image each, in the
same order, with notes. Use only what the sources show or say. Do not make up names, places or dates.
Reply in the language of the question, in exactly this form:
ANSWER: <your answer, citing sources like [1]>
KNOWN: <yes, or no when the sources do not answer the question; then say in ANSWER that you do not know>
SOURCES: <each source you used and how you know: 1=OBSERVED, 2=USER_STATED, 3=INFERRED>
OBSERVED means you can see it in the image. USER_STATED means it comes from the user's memo. INFERRED means you guessed it."""

        /** Earlier turns kept for a follow-up; each image already costs about 256 of the 2,048 tokens. */
        const val MAX_EARLIER = 3

        /** When and where source [1] was taken, and the chat so far. */
        fun context(takenAtMs: Long?, place: String?, earlier: List<Pair<String, String>>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
            val taken = takenAtMs?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone).truncatedTo(ChronoUnit.MINUTES) }
            if (taken != null || place != null) {
                append("[1] was taken")
                taken?.let { append(" on ").append(it) }
                place?.let { append(" in ").append(it) }
                append(".\n")
            }
            earlier.takeLast(MAX_EARLIER).forEach { (q, a) -> append("Earlier question: ").append(q).append("\nEarlier answer: ").append(a).append('\n') }
        }

        /** The question, one line of notes per source, and [context] before the question. */
        fun prompt(question: String, sources: List<SearchHit>, context: String = ""): String = buildString {
            sources.forEachIndexed { i, hit ->
                append("[${i + 1}] ")
                append(if (hit.item.kind == MediaKind.VIDEO) "video" else "photo")
                hit.timestampMs?.let { append(", scene at ${it / 1000} s") }
                val memo = hit.text.takeIf { Evidence.MEMO in hit.evidence }
                val read = hit.text.takeIf { Evidence.TEXT in hit.evidence && Evidence.MEMO !in hit.evidence }
                memo?.let { append(". User's memo: \"$it\"") }
                read?.let { append(". Text read in the image: \"$it\"") }
                append('\n')
            }
            append(context)
            append("Question: ").append(question.trim())
        }

        private val THINKING = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        private val CITED = Regex("""\[(\d+)]""")
        private val PAIR = Regex("""(\d+)\s*=\s*(OBSERVED|USER_STATED|INFERRED)""", RegexOption.IGNORE_CASE)

        /** The answer part of a reply still being written; nothing while the model thinks or starts its first line. */
        fun visibleAnswer(partial: String): String {
            val text = partial.replace(THINKING, "")
            if ("<think>" in text) return ""
            if ("ANSWER:" !in text) return if ("ANSWER:".startsWith(text.trim())) "" else text.trim()
            return text.substringAfter("ANSWER:").substringBefore("KNOWN:").substringBefore("SOURCES:").trim()
        }

        /**
         * Reads the reply's three lines. A reply out of form keeps its text, and the sources it cites
         * count as guessed: nothing then claims to be seen or stated that the model did not mark so, and a
         * source without a memo is never what the user stated.
         */
        fun parseAnswer(reply: String, sources: List<SearchHit>): AskResult.Answered {
            val text = reply.replace(THINKING, "").trim()
            val answer = text.substringAfter("ANSWER:", text).substringBefore("KNOWN:").substringBefore("SOURCES:").trim()
            val known = !text.substringAfter("KNOWN:", "yes").trim().startsWith("no", ignoreCase = true)
            val bases = PAIR.findAll(text.substringAfter("SOURCES:", "")).associate { it.groupValues[1].toInt() to Basis.valueOf(it.groupValues[2].uppercase()) }
            val cited = bases.keys + CITED.findAll(answer).map { it.groupValues[1].toInt() }
            val citations = cited.distinct().sorted().mapNotNull { n ->
                sources.getOrNull(n - 1)?.let { hit ->
                    // Only a memo the user wrote is what the user stated.
                    val basis = bases[n]?.takeUnless { it == Basis.USER_STATED && Evidence.MEMO !in hit.evidence } ?: Basis.INFERRED
                    Citation(hit, basis, n)
                }
            }
            return AskResult.Answered(answer, known, citations)
        }
    }
}
