package dev.irohaco.core.application

import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AnswerModelSettings
import dev.irohaco.core.contracts.AnswerRuntime
import dev.irohaco.core.contracts.AnswerSession
import dev.irohaco.core.contracts.CredentialStore
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.EgressPolicy
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteFailure
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteModelStore
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.RemoteSessions
import dev.irohaco.core.contracts.TaskType
import dev.irohaco.core.contracts.IndexImageReader
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
/** Answers rest on the search results, cite them by how they are known, and say when they do not know (RAG-001〜003). */
class AskLibraryTest {
    private val photo = SearchHit(TimelineItem("p", MediaKind.IMAGE, "content://p", 0), null, setOf(Evidence.MEANING), null)
    private val memo = SearchHit(TimelineItem("m", MediaKind.IMAGE, "content://m", 0), null, setOf(Evidence.MEMO), "Grandma's 80th birthday")
    private val scene = SearchHit(TimelineItem("v", MediaKind.VIDEO, "content://v", 0), 72_000, setOf(Evidence.MEANING), null)

    private val settings = object : AnswerModelSettings {
        override val answerModel = MutableStateFlow<String?>(null)
        override suspend fun setAnswerModel(id: String) {
            answerModel.value = id
        }
    }
    private val read = mutableListOf<String>()
    private val images = object : IndexImageReader {
        override suspend fun read(contentUri: String) = byteArrayOf(1).also { read += contentUri }
        override suspend fun readFrame(contentUri: String, timestampMs: Long) = byteArrayOf(2).also { read += "$contentUri@$timestampMs" }
    }
    private var reply = ""
    private var failing = false
    private var loads = 0
    private var closed = 0
    private val asked = mutableListOf<Pair<Int, String>>()
    private var warmed = 0
    private val runtime = AnswerRuntime { _, _ ->
        loads++
        object : AnswerSession {
            override suspend fun answer(instruction: String, images: List<ByteArray>, question: String, onText: (String) -> Unit): String {
                check(!failing) { "The model's process died" }
                asked += images.size to question
                // The reply arrives in pieces, as the runtime streams it.
                reply.chunked(8).runningReduce { so, piece -> so + piece }.forEach(onText)
                return reply
            }
            override suspend fun warm() {
                warmed++
            }
            override fun close() {
                closed++
            }
        }
    }

    private val claude = RemoteModel("claude", "anthropic", "claude-x", "Claude", RemoteAnswering.NEEDED + ModelCapability.STREAMING)
    private var granted = true
    private var remoteFails = false
    private val policyAsked = mutableListOf<Set<EgressPayload>>()
    private val remoteAsked = mutableListOf<String>()
    private val remote = RemoteAnswering(
        object : RemoteModelStore {
            override val models = flowOf(listOf(claude))
            override suspend fun model(profileId: String) = claude.takeIf { it.profileId == profileId }
            override suspend fun connection(id: String) = RemoteConnection(id, RemoteProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1")
            override suspend fun credentialRef(connectionId: String) = "ref"
            override suspend fun addModel(model: RemoteModel) = Unit
            override suspend fun removeModel(profileId: String) = Unit
        },
        object : CredentialStore {
            override fun save(secret: String) = "ref"
            override fun read(ref: String) = "key"
            override fun delete(ref: String) = Unit
        },
        RemoteSessions { _, _, _, authorize ->
            object : AnswerSession {
                override suspend fun answer(instruction: String, images: List<ByteArray>, question: String, onText: (String) -> Unit): String {
                    check(authorize() == EgressDecision.Allowed)
                    if (remoteFails) throw RemoteCallException(RemoteFailure.RateLimited(30))
                    remoteAsked += question
                    return reply
                }
                override suspend fun warm() = Unit
                override fun close() = Unit
            }
        },
        object : EgressPolicy {
            override suspend fun authorize(task: TaskType, profileId: String, payloads: Set<EgressPayload>, assetIds: Set<String>): EgressDecision {
                policyAsked += payloads
                return if (granted) EgressDecision.Allowed else EgressDecision.Denied(payloads, emptySet())
            }
        },
    )

    private fun TestScope.ask(installed: Set<String>) = AskLibrary(
        installed = { id -> "/models/$id".takeIf { id in installed } },
        settings = settings,
        runtime = runtime,
        images = images,
        io = StandardTestDispatcher(testScheduler),
        scope = backgroundScope,
        backend = { AiBackend.CPU },
        remote = remote,
    )

    @Test
    fun `the model sees the photos and frames, and cites them as seen, stated or guessed`() = runTest {
        reply = "<think>hmm</think>ANSWER: It was her 80th birthday [2], at the harbour [1].\nKNOWN: yes\nSOURCES: 1=OBSERVED, 2=USER_STATED, 3=USER_STATED"
        val answer = assertIs<AskResult.Answered>(ask(setOf(ModelCatalog.GEMMA_4_E2B.id)).ask("Whose birthday?", listOf(photo, memo, scene)))
        assertEquals("It was her 80th birthday [2], at the harbour [1].", answer.text)
        assertTrue(answer.known)
        // A scene has no memo, so the model cannot have it from the user.
        assertEquals(listOf("p" to Basis.OBSERVED, "m" to Basis.USER_STATED, "v" to Basis.INFERRED), answer.citations.map { it.hit.item.assetId to it.basis })
        assertEquals(listOf(1, 2, 3), answer.citations.map { it.number })
        assertEquals(listOf("content://p", "content://m", "content://v@72000"), read)
        assertEquals(3, asked.single().first)
        assertTrue("User's memo: \"Grandma's 80th birthday\"" in asked.single().second)
    }

    @Test
    fun `a reply out of form keeps its text and counts its citations as guessed`() = runTest {
        reply = "Probably a beach trip [1]."
        val answer = assertIs<AskResult.Answered>(ask(setOf(ModelCatalog.GEMMA_4_E2B.id)).ask("Where?", listOf(photo)))
        assertEquals("Probably a beach trip [1].", answer.text)
        assertEquals(listOf(Basis.INFERRED), answer.citations.map { it.basis })

        reply = "ANSWER: I don't know.\nKNOWN: no\nSOURCES:"
        val unknown = assertIs<AskResult.Answered>(ask(setOf(ModelCatalog.GEMMA_4_E2B.id)).ask("Where?", listOf(photo)))
        assertEquals(false, unknown.known)
        assertEquals(emptyList(), unknown.citations)
    }

    @Test
    fun `the picked model runs when installed, else the first installed one, and none means no answer`() = runTest {
        assertEquals(AskResult.NoModel, ask(emptySet()).ask("Where?", listOf(photo)))
        val library = ask(setOf(ModelCatalog.LFM_3_VL_2B.id, ModelCatalog.GEMMA_4_E4B.id))
        assertEquals("/models/${ModelCatalog.GEMMA_4_E4B.id}", library.modelPath())
        library.choose(ModelCatalog.LFM_3_VL_2B.id)
        assertEquals("/models/${ModelCatalog.LFM_3_VL_2B.id}", library.modelPath())
        assertEquals(AskResult.NothingFound, library.ask("Where?", emptyList()))
    }

    @Test
    fun `the answer shows while it is written, without the reply's other lines`() = runTest {
        reply = "<think>where</think>ANSWER: A pier at dusk [1].\nKNOWN: yes\nSOURCES: 1=OBSERVED"
        val shown = mutableListOf<String>()
        ask(setOf(ModelCatalog.GEMMA_4_E2B.id)).ask("Where?", listOf(photo)) { shown += it }
        assertTrue(shown.none { "<think>" in it || "KNOWN" in it || "ANSWER" in it }, shown.toString())
        assertEquals("A pier at dusk [1].", shown.last())
        assertTrue(shown.any { it.isNotEmpty() && it != shown.last() }) // it grew before the end
    }

    @Test
    fun `the model loads once, warmed up before the first question, and is freed when it sits unused`() = runTest {
        reply = "ANSWER: A pier [1].\nKNOWN: yes\nSOURCES: 1=OBSERVED"
        val library = ask(setOf(ModelCatalog.GEMMA_4_E2B.id))
        library.warmUp()
        runCurrent()
        assertEquals(1, warmed)
        library.ask("Where?", listOf(photo))
        library.ask("Where?", listOf(photo))
        assertEquals(1, loads, "loads")
        assertEquals(0, closed, "closed while in use")
        advanceTimeBy(AskLibrary.IDLE_MS + 1)
        runCurrent()
        assertEquals(1, closed, "closed when unused")
    }

    @Test
    fun `a failed answer frees the model, so the next question loads it again`() = runTest {
        reply = "ANSWER: A pier [1].\nKNOWN: yes\nSOURCES: 1=OBSERVED"
        val library = ask(setOf(ModelCatalog.GEMMA_4_E2B.id))
        failing = true
        assertIs<AskResult.Failed>(library.ask("Where?", listOf(photo)))
        failing = false
        assertIs<AskResult.Answered>(library.ask("Where?", listOf(photo)))
        assertEquals(2, loads)
    }

    @Test
    fun `a photo in the viewer is asked about with its memo, time, place and the earlier turns`() = runTest {
        reply = "ANSWER: At the harbour [1].\nKNOWN: yes\nSOURCES: 1=USER_STATED"
        val video = TimelineItem("v", MediaKind.VIDEO, "content://v", 0)
        val earlier = listOf("q1" to "a1", "q2" to "a2", "q3" to "a3", "q4" to "a4")
        val answer = assertIs<AskResult.Answered>(
            ask(setOf(ModelCatalog.GEMMA_4_E2B.id)).askAbout("And who?", video, 5_000, listOf("Harbour trip", " "), 0, "Kyoto", earlier),
        )
        assertEquals(Basis.USER_STATED, answer.citations.single().basis)
        assertEquals(listOf("content://v@5000"), read)
        val prompt = asked.single().second
        assertTrue("User's memo: \"Harbour trip\"" in prompt, prompt)
        assertTrue("[1] was taken on " in prompt && " in Kyoto." in prompt, prompt)
        assertTrue("q1" !in prompt && "Earlier question: q4\nEarlier answer: a4" in prompt, prompt)
        assertTrue(prompt.endsWith("Question: And who?"), prompt)
    }

    @Test
    fun `a photo with no memo cannot be what the user stated`() = runTest {
        reply = "ANSWER: A pier [1].\nKNOWN: yes\nSOURCES: 1=USER_STATED"
        val answer = assertIs<AskResult.Answered>(
            ask(setOf(ModelCatalog.GEMMA_4_E2B.id)).askAbout("Where?", photo.item, null, emptyList(), null, null, emptyList()),
        )
        assertEquals(Basis.INFERRED, answer.citations.single().basis)
        assertEquals(listOf("content://p"), read)
        assertTrue("was taken" !in asked.single().second)
    }

    @Test
    fun `a remote model asks first, then gets exactly what the question carries`() = runTest {
        reply = "ANSWER: Her birthday [2].\nKNOWN: yes\nSOURCES: 2=USER_STATED"
        val library = ask(setOf(ModelCatalog.GEMMA_4_E2B.id))
        library.choose("claude")
        val consent = assertIs<AskResult.NeedsConsent>(library.ask("Whose birthday?", listOf(photo, memo, scene)))
        assertEquals("api.anthropic.com", consent.host)
        assertEquals(setOf(EgressPayload.QUERY_TEXT, EgressPayload.IMAGE_PIXELS, EgressPayload.VIDEO_FRAMES, EgressPayload.USER_ANNOTATIONS), consent.payloads)
        assertTrue(read.isEmpty() && remoteAsked.isEmpty()) // nothing read, nothing sent before the user agrees
        consent.agree()
        assertEquals(claude.displayName, assertIs<AskResult.Answered>(library.ask("Whose birthday?", listOf(photo, memo, scene))).remoteModel)
        assertEquals(1, remoteAsked.size)
        assertEquals(0, loads) // the local model was never loaded
        // The policy saw the same payloads before the photos were read, and again before the request.
        assertEquals(List(2) { consent.payloads }, policyAsked)
    }

    @Test
    fun `a refused or failing remote model says why and never falls back to the local one`() = runTest {
        val library = ask(setOf(ModelCatalog.GEMMA_4_E2B.id))
        library.choose("claude")
        assertIs<AskResult.NeedsConsent>(library.ask("Where?", listOf(photo))).agree()
        granted = false
        val denied = assertIs<AskResult.Failed>(library.ask("Where?", listOf(photo)))
        assertIs<EgressDeniedException>(denied.cause)
        assertTrue(read.isEmpty())
        granted = true
        remoteFails = true
        val failed = assertIs<AskResult.Failed>(library.ask("Where?", listOf(photo)))
        assertEquals(RemoteFailure.RateLimited(30), (failed.cause as RemoteCallException).failure)
        assertEquals(0, loads)
    }

    @Test
    fun `a remote model is not told where or when a photo was taken`() = runTest {
        reply = "ANSWER: A pier [1].\nKNOWN: yes\nSOURCES: 1=OBSERVED"
        val library = ask(setOf(ModelCatalog.GEMMA_4_E2B.id))
        library.choose("claude")
        assertIs<AskResult.NeedsConsent>(library.askAbout("Where?", photo.item, null, emptyList(), 0, "Hakodate", emptyList())).agree()
        library.askAbout("Where?", photo.item, null, emptyList(), 0, "Hakodate", emptyList())
        assertTrue("Hakodate" !in remoteAsked.single() && "was taken" !in remoteAsked.single())
    }
}
