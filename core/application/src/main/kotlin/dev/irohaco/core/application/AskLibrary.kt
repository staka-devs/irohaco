package dev.irohaco.core.application

import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AnswerModelSettings
import dev.irohaco.core.contracts.AnswerRuntime
import dev.irohaco.core.contracts.AnswerSession
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.GenerationParameter
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.IndexImageReader
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.PlaceEvidenceStore
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.TaskType
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** Where a cited fact comes from (RAG-003): seen in the image, written by the user, or guessed. */
enum class Basis { OBSERVED, USER_STATED, INFERRED }

/**
 * A search result the answer rests on, tapped to open the photo or the video at its scene (RAG-002).
 * [number] is how the answer's text cites it, as in "[2]".
 */
data class Citation(val hit: SearchHit, val basis: Basis, val number: Int)

sealed interface AskResult {
    /**
     * [known] is false when the sources do not answer the question; the text then says so (RAG-003).
     * [remoteModel] names the remote model that answered, or is null for the model on this device.
     */
    data class Answered(val text: String, val known: Boolean, val citations: List<Citation>, val remoteModel: String? = null, val thought: String = "") : AskResult

    /** No answer model is installed; Settings > AI downloads one. */
    data object NoModel : AskResult

    /** Nothing was found to answer from. */
    data object NothingFound : AskResult

    data class Failed(val cause: Throwable) : AskResult

    /**
     * The picked model is remote and has not had a question in this run of the app: nothing is sent until
     * the user agrees, having seen where it goes and what it carries (API-007). [agree] lets the next ask through.
     */
    class NeedsConsent(val model: String, val service: String, val payloads: Set<EgressPayload>, val agree: () -> Unit) : AskResult
}

/**
 * A question read as a photo search by the answering model: the words to search for (a place it names first),
 * capture times in [fromMs, untilMs), the kind, and whether the user wants to see photos rather than an answer.
 */
data class SearchPlan(val words: String, val fromMs: Long?, val untilMs: Long?, val kind: MediaKind?, val show: Boolean, val place: String? = null)

/** An answer still being written: what the model thought so far, then the answer's text so far. */
data class AskPartial(val thought: String, val text: String)

/** When and where a source was taken, written beside it for the model; [guessed] when the place is only a guess. */
data class SourceFacts(val takenAtMs: Long?, val place: String?, val guessed: Boolean = false)

/** Asks a model to read a message as a photo search, as JSON (AskLibrary.plan). */
const val PLAN_INSTRUCTION =
    """You turn a message about the user's own photos and videos into a search of them. Reply with one JSON
object and nothing else:
{"words": [a few words for what the photos show, in the language of the message; leave out question words, dates,
places and words such as photo or video; when the message names nothing to look for, words for photos that would
answer it, such as travel and sightseeing for "where did I go?"],
 "place": a place the message names, or null,
 "from": the first day it means as "YYYY-MM-DD", or null,
 "until": the last day it means as "YYYY-MM-DD", or null,
 "kind": "photo", "video" or "any",
 "show": true when the user wants to see photos, false when the user asks something to be answered}
When a previous search is given and the message only changes it, such as "and the year before?" or "videos?", keep
what it does not change."""

/** A model that can answer; [service] is the name of the service it runs at, or null for one on this device. */
data class AnswerChoice(
    val id: String,
    val name: String,
    val service: String? = null,
    /** The thinking depths a question can ask for: the service's effort values, or [THINK_OFF] and [THINK_ON] on the device. */
    val efforts: List<String> = emptyList(),
)

/** A model on the device answers without its thoughts, or with them, as it does unless told. */
const val THINK_OFF = "off"
const val THINK_ON = "on"

/** [current] is the model a question goes to, or null when none can answer. */
data class AnswerChoices(val all: List<AnswerChoice>, val current: String?, val effort: String? = null)

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
    /** Remote models the user registered; a remote pick never falls back to the local one when refused or failing (AC-009). */
    private val remote: RemoteAnswering? = null,
    /** Where each source was taken, written beside it; null leaves the places out. */
    private val places: PlaceEvidenceStore? = null,
) {
    val chosen: Flow<String?> = settings.answerModel

    /** The depth the next questions ask for, kept until another model is chosen; null leaves the model's own or saved one. */
    private val effort = MutableStateFlow<String?>(null)

    /**
     * The models that can answer now, on this device or at a service, and the one a question goes to.
     * shortcut: a model downloaded while a screen shows this is listed once the pick or the services change.
     */
    val choices: Flow<AnswerChoices> = combine(
        settings.answerModel,
        remote?.models ?: flowOf(emptyList()),
        remote?.serviceNames ?: flowOf(emptyMap()),
        effort,
    ) { picked, services, _, effort ->
        val local = catalog.filter { installed(it.id) != null }.map { AnswerChoice(it.id, it.displayName, efforts = listOf(THINK_OFF, THINK_ON)) }
        val far = services.filter { it.capabilities.containsAll(RemoteAnswering.NEEDED) }.map { model ->
            val connection = remote?.connection(model)
            AnswerChoice(model.profileId, model.displayName, connection?.serviceName.orEmpty(), connection?.protocol?.let { effortOf(it)?.values }.orEmpty())
        }
        val all = local + far
        AnswerChoices(all, picked?.takeIf { id -> all.any { it.id == id } } ?: local.firstOrNull()?.id, effort)
    }.flowOn(io)

    fun chooseEffort(value: String?) {
        effort.value = value
    }

    suspend fun choose(id: String) {
        settings.setAnswerModel(id)
        effort.value = null
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

    private val consented = ConcurrentHashMap.newKeySet<String>()

    /** The picked model when it is a remote one that can answer. */
    private suspend fun remoteModel(): RemoteModel? = settings.answerModel.first()?.let { remote?.model(it) }

    /** Loads the model in the background, so a question asked soon after does not wait for it. */
    fun warmUp() {
        scope.launch {
            if (remoteModel() != null) return@launch // nothing to load, and nothing is sent before a question
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
        onText: (AskPartial) -> Unit = {},
    ): AskResult {
        val memo = memos.filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { null }
        val hit = SearchHit(item, frameMs ?: 0L.takeIf { item.kind == MediaKind.VIDEO }, setOfNotNull(Evidence.MEMO.takeIf { memo != null }), memo)
        // The time stays on the device; a remote model gets the place only as the town name, and only once granted.
        val model = remoteModel()
        val sent = place?.takeIf { model == null || placeGranted(model) }
        return ask(question, listOf(hit), context(takenAtMs.takeIf { model == null }, sent, earlier), sent != null, onText)
    }

    /**
     * How the answering model reads [question] as a photo search, as JSON and without thinking, so it takes a few
     * seconds; the answer, if one is wanted, is a second request. [previous] is the search just made, so a follow-up
     * such as "and the year before?" can change it. Null when no model is ready, a service is not agreed to or granted
     * the question yet, or the reply cannot be read.
     */
    suspend fun plan(question: String, now: ZonedDateTime, previous: ReadQuery? = null): SearchPlan? {
        val prompt = buildString {
            append("Today: ").append(now.toLocalDate()).append('\n')
            previous?.let { append("Previous search: ").append(planJson(it, now.zone)).append('\n') }
            append("Question: ").append(question.trim())
        }
        return try {
            val model = remoteModel()
            val reply = if (model != null) {
                val remote = checkNotNull(remote)
                val text = setOf(EgressPayload.QUERY_TEXT)
                if (model.profileId !in consented || remote.policy.authorize(TaskType.RAG, model.profileId, text, emptySet()) != EgressDecision.Allowed) return null
                remote.open(model, text, emptySet()).use { withContext(io) { it.answer(PLAN_INSTRUCTION, emptyList(), prompt, think = false) } }
            } else {
                val path = modelPath() ?: return null
                withSession(path) { it.answer(PLAN_INSTRUCTION, emptyList(), prompt, think = false) }
            }
            parsePlan(reply, now.zone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null // the fixed rules read the question instead
        }
    }

    private suspend fun placeGranted(model: RemoteModel): Boolean =
        remote?.policy?.authorize(TaskType.RAG, model.profileId, setOf(EgressPayload.PLACE_NAME), emptySet()) == EgressDecision.Allowed

    /**
     * [onText] gets the answer's text so far while the model writes it; [context] goes just before the question.
     * Each source goes with when and where it was taken, so questions about time and place can be answered.
     */
    suspend fun ask(question: String, hits: List<SearchHit>, context: String = "", onText: (AskPartial) -> Unit = {}): AskResult =
        ask(question, hits, context, sendsPlace = false, onText, described = true)

    private suspend fun ask(question: String, hits: List<SearchHit>, context: String, sendsPlace: Boolean, onText: (AskPartial) -> Unit, described: Boolean = false): AskResult {
        remoteModel()?.let { return askRemote(it, question, hits, context, onText, sendsPlace, described) }
        val path = modelPath() ?: return AskResult.NoModel
        val sources = hits.take(MAX_SOURCES)
        if (question.isBlank() || sources.isEmpty()) return AskResult.NothingFound
        return try {
            val pictures = withContext(io) {
                sources.map { hit -> hit.timestampMs?.let { images.readFrame(hit.item.contentUri, it) } ?: images.read(hit.item.contentUri) }
            }
            val facts = if (described) facts(sources, withTime = true) else emptyMap()
            val reply = withSession(path) { session ->
                session.answer(INSTRUCTION, pictures, prompt(question, sources, context, facts), think = effort.value != THINK_OFF) {
                    onText(AskPartial(visibleThought(it), visibleAnswer(it)))
                }
            }
            parseAnswer(reply, sources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) { // a native library that fails to load is an Error, not an Exception
            release() // the model's process may have died with it; the next question starts a new one
            AskResult.Failed(e)
        }
    }

    /** When ([withTime]) and where each of [sources] was taken, as far as the app knows. */
    private suspend fun facts(sources: List<SearchHit>, withTime: Boolean): Map<String, SourceFacts> {
        val known = places?.placesOf(sources.map { it.item.assetId }).orEmpty()
        return sources.associate { hit ->
            val place = known[hit.item.assetId]
            hit.item.assetId to SourceFacts(hit.item.sortTimeMs.takeIf { withTime && it > 0 }, place?.placeName, place?.inferred == true)
        }
    }

    private suspend fun askRemote(
        model: RemoteModel,
        question: String,
        hits: List<SearchHit>,
        context: String,
        onText: (AskPartial) -> Unit,
        sendsPlace: Boolean,
        described: Boolean,
    ): AskResult {
        val remote = checkNotNull(remote)
        val sources = hits.take(MAX_SOURCES)
        if (question.isBlank() || sources.isEmpty()) return AskResult.NothingFound
        // The time stays on the device; the places go only as town names, and only once granted.
        val named = if (described) facts(sources, withTime = false).filterValues { it.place != null } else emptyMap()
        val facts = named.takeIf { it.isNotEmpty() && placeGranted(model) }.orEmpty()
        val payloads = payloads(sources) + setOfNotNull(EgressPayload.PLACE_NAME.takeIf { sendsPlace || facts.isNotEmpty() })
        val assets = sources.map { it.item.assetId }.toSet()
        if (model.profileId !in consented) {
            val service = remote.connection(model)?.serviceName.orEmpty()
            return AskResult.NeedsConsent(model.displayName, service, payloads) { consented += model.profileId }
        }
        return try {
            val decision = remote.policy.authorize(TaskType.RAG, model.profileId, payloads, assets)
            if (decision is EgressDecision.Denied) return AskResult.Failed(EgressDeniedException(decision))
            val pictures = withContext(io) {
                sources.map { hit -> hit.timestampMs?.let { images.readFrame(hit.item.contentUri, it) } ?: images.read(hit.item.contentUri) }
            }
            val depth = effort.value?.let { value -> remote.connection(model)?.protocol?.let(::effortOf)?.let { mapOf(it.path to value) } }.orEmpty()
            val reply = remote.open(model, payloads, assets, depth).use { session ->
                withContext(io) { session.answer(INSTRUCTION, pictures, prompt(question, sources, context, facts)) { onText(AskPartial(visibleThought(it), visibleAnswer(it))) } }
            }
            parseAnswer(reply, sources).copy(remoteModel = model.displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AskResult.Failed(e)
        }
    }

    suspend fun release() {
        lock.withLock {
            closeLater()
            loadedPath = null
        }
    }

    /** Called with [lock] held; the native close may never return, so questions do not wait on it (#215). */
    private fun closeLater() {
        session?.let { scope.launch(io) { nativeClose.withLock { it.close() } } }
        session = null
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
        closeLater()
        val chosenBackend = backend()
        return withContext(io) { runtime.load(path, chosenBackend).also { it.warm() } }.also {
            session = it
            loadedPath = path
        }
    }

    internal companion object {
        const val IDLE_MS = 5 * 60_000L

        /** The request field a protocol sets its thinking depth with, if it has one. */
        fun effortOf(protocol: RemoteProtocol): GenerationParameter? = GenerationParameter.of(protocol).firstOrNull { it.path.endsWith("effort") }

        /** Each image costs a model about 256 tokens; the smallest context here is 2,048. */
        const val MAX_SOURCES = 4

        const val INSTRUCTION =
            """You answer questions about the user's own photos and videos. You are given numbered sources: one image each, in the
same order, with notes. Use only what the sources show or say. Do not make up names, places or dates.
Reply in the language of the question, in exactly this form:
ANSWER: <your answer, citing sources like [1]>
KNOWN: <yes, or no when the sources do not answer the question; then say in ANSWER that you do not know>
SOURCES: <each source you used and how you know: 1=OBSERVED, 2=USER_STATED, 3=INFERRED>
OBSERVED means you can see it in the image. USER_STATED means it comes from the user's memo. INFERRED means you guessed it.
The notes may say when and where each source was taken; use them for questions about time and place. A place marked
as guessed is INFERRED."""

        /** A search as [PLAN_INSTRUCTION]'s JSON, given back to the model as the previous search. */
        fun planJson(read: ReadQuery, zone: ZoneId): String {
            fun day(ms: Long?) = ms?.let { "\"" + Instant.ofEpochMilli(it).atZone(zone).toLocalDate() + "\"" } ?: "null"
            val words = if (read.hasWords) "[\"" + read.rest.replace("\"", "") + "\"]" else "[]"
            val kind = when (read.kind) {
                MediaKind.IMAGE -> "photo"
                MediaKind.VIDEO -> "video"
                null -> "any"
            }
            val place = read.place?.let { "\"" + it.replace("\"", "") + "\"" } ?: "null"
            return """{"words": $words, "place": $place, "from": ${day(read.fromMs)}, "until": ${day(read.untilMs?.minus(1))}, "kind": "$kind"}"""
        }

        /** Reads [PLAN_INSTRUCTION]'s JSON, also inside a code fence or after other text; null when it has no "show". */
        fun parsePlan(reply: String, zone: ZoneId): SearchPlan? {
            val json = reply.replace(THINKING, "").substringAfter('{', "").substringBeforeLast('}', "")
            fun raw(key: String) = Regex(""""$key"\s*:\s*("(?:[^"\\]|\\.)*"|\[[^\]]*]|true|false|null)""").find(json)?.groupValues?.get(1)
            fun strings(value: String?) = STRING.findAll(value.orEmpty()).map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").trim() }.filter { it.isNotEmpty() }.toList()
            val show = when (raw("show")) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            fun day(key: String) = strings(raw(key)).firstOrNull()?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            val from = day("from")
            val until = day("until")
            val span = if (from != null && until != null && !until.isBefore(from)) from to until.plusDays(1) else null
            return SearchPlan(
                // The place is a condition on where, apart from the words looked for by meaning.
                words = strings(raw("words")).joinToString(" "),
                place = strings(raw("place")).firstOrNull(),
                fromMs = span?.first?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
                untilMs = span?.second?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
                kind = when (strings(raw("kind")).firstOrNull()?.lowercase()) {
                    "photo", "photos", "image" -> MediaKind.IMAGE
                    "video", "videos" -> MediaKind.VIDEO
                    else -> null
                },
                show = show,
            )
        }

        private val STRING = Regex(""""((?:[^"\\]|\\.)*)"""")

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

        /** What a question about [sources] carries, matching what [prompt] writes and the images sent with it. */
        fun payloads(sources: List<SearchHit>): Set<EgressPayload> = buildSet {
            add(EgressPayload.QUERY_TEXT)
            if (sources.any { it.timestampMs == null }) add(EgressPayload.IMAGE_PIXELS)
            if (sources.any { it.timestampMs != null }) add(EgressPayload.VIDEO_FRAMES)
            if (sources.any { it.text != null && Evidence.MEMO in it.evidence }) add(EgressPayload.USER_ANNOTATIONS)
            if (sources.any { it.text != null && Evidence.TEXT in it.evidence && Evidence.MEMO !in it.evidence }) add(EgressPayload.OCR_TEXT)
        }

        /** The question, one line of notes per source with its [facts], and [context] before the question. */
        fun prompt(
            question: String,
            sources: List<SearchHit>,
            context: String = "",
            facts: Map<String, SourceFacts> = emptyMap(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): String = buildString {
            sources.forEachIndexed { i, hit ->
                append("[${i + 1}] ")
                append(if (hit.item.kind == MediaKind.VIDEO) "video" else "photo")
                hit.timestampMs?.let { append(", scene at ${it / 1000} s") }
                facts[hit.item.assetId]?.let { fact ->
                    fact.takenAtMs?.let { append(", taken ").append(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone).truncatedTo(ChronoUnit.MINUTES)) }
                    fact.place?.let { append(if (fact.guessed) ", place guessed from photos taken near it in time or the location history: " else ", taken in ").append(it) }
                }
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

        /** What the model thought, inside <think>, so far or in all. */
        fun visibleThought(partial: String): String {
            val open = partial.indexOf("<think>").takeIf { it >= 0 } ?: return ""
            return partial.substring(open + "<think>".length).substringBefore("</think>").trim()
        }

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
            return AskResult.Answered(answer, known, citations, thought = visibleThought(reply))
        }
    }
}
