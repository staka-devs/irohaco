package dev.irohaco.core.application

import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.CredentialStore
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.EgressPolicy
import dev.irohaco.core.contracts.EmbeddingSession
import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.ModelCapability.INPUT_IMAGE
import dev.irohaco.core.contracts.ModelCapability.INPUT_TEXT
import dev.irohaco.core.contracts.ModelCapability.OUTPUT_EMBEDDING
import dev.irohaco.core.contracts.ModelNotInstalledException
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteEmbeddingSessions
import dev.irohaco.core.contracts.RemoteFailure
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteModelStore
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.TaskType
import dev.irohaco.core.contracts.VectorHit
import dev.irohaco.core.contracts.VectorSearchStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A remote model fills and queries its own space, each request checked first, and nothing else stands in for it (ADR-0032, AC-009). */
class RemoteEmbeddingTest {
    private val gemini = RemoteModel("gem", "c", "gemini-embedding", "Gemini", setOf(INPUT_TEXT, INPUT_IMAGE, OUTPUT_EMBEDDING), dimension = 3)
    private val textOnly = RemoteModel("oai", "c", "text-embedding", "OpenAI", setOf(INPUT_TEXT, OUTPUT_EMBEDDING), dimension = 3)
    private val models = MutableStateFlow(listOf(gemini, textOnly))
    private val store = object : RemoteModelStore {
        override val models = this@RemoteEmbeddingTest.models
        override suspend fun model(profileId: String) = models.value.firstOrNull { it.profileId == profileId }
        override suspend fun connection(id: String) = RemoteConnection(id, RemoteProtocol.GEMINI_EMBED_CONTENT, "https://example.test")
        override suspend fun credentialRef(connectionId: String) = "ref"
        override suspend fun addModel(model: RemoteModel) = Unit
        override suspend fun removeModel(profileId: String) = Unit
    }
    private val credentials = object : CredentialStore {
        override fun save(secret: String) = "ref"
        override fun read(ref: String) = "key"
        override fun delete(ref: String) = Unit
    }

    /** What each request asked the policy: the task, the payloads and the files. */
    private val asked = mutableListOf<Triple<TaskType, Set<EgressPayload>, Set<String>>>()
    private var allowed = true
    private val policy = object : EgressPolicy {
        override suspend fun authorize(task: TaskType, profileId: String, payloads: Set<EgressPayload>, assetIds: Set<String>): EgressDecision {
            asked += Triple(task, payloads, assetIds)
            return if (allowed) EgressDecision.Allowed else EgressDecision.Denied(payloads, emptySet())
        }
    }
    private val sent = mutableListOf<String>()
    private var failure: RemoteFailure? = null

    /** Like the real session: it asks first, and sends only when allowed. */
    private val sessions = RemoteEmbeddingSessions { _, _, model, authorize ->
        object : EmbeddingSession {
            override val backend = AiBackend.CPU
            override fun embedText(text: String) = send(EgressPayload.QUERY_TEXT, "${model.profileId}:$text")
            override fun embedImage(bytes: ByteArray) = send(EgressPayload.IMAGE_PIXELS, "${model.profileId}:image")
            private fun send(payload: EgressPayload, what: String): FloatArray {
                val decision = runBlocking { authorize(payload) }
                if (decision is EgressDecision.Denied) throw EgressDeniedException(decision)
                failure?.let { throw RemoteCallException(it) }
                sent += what
                return floatArrayOf(1f, 0f, 0f)
            }
            override fun close() = Unit
        }
    }

    private fun remote(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        RemoteEmbedding(store, credentials, sessions, policy, StandardTestDispatcher(scheduler))

    @Test
    fun `only a model that reads images and has a measured length gets a space`() = runTest {
        val remote = remote(testScheduler)
        assertEquals(RemoteEmbedding.spec(gemini), remote.spec("gem"))
        assertEquals(3, remote.spec("gem")?.dimension)
        assertNull(remote.spec("gem")?.queryPrefix)
        assertNull(remote.spec("oai"))
        models.value = listOf(gemini.copy(dimension = null))
        assertNull(remote.spec("gem"))
    }

    @Test
    fun `the local model is left to the caller, and a removed remote model is never replaced`() = runTest {
        val remote = remote(testScheduler)
        assertNull(remote.embedder(ImageSpaces.LOCAL.modelProfileId))
        models.value = emptyList()
        assertFailsWith<ModelNotInstalledException> { remote.embedder("gem") }
    }

    @Test
    fun `each request is checked for its task, what it carries and its file`() = runTest {
        val model = remote(testScheduler).embedder("gem")!!
        model.query("a pier")
        model.image(byteArrayOf(1), "photo")
        model.frame(byteArrayOf(1), "video")
        model.memo("Grandma's house", "photo")
        assertEquals(
            listOf(
                Triple(TaskType.SEARCH_QUERY, setOf(EgressPayload.QUERY_TEXT), emptySet()),
                Triple(TaskType.IMAGE_INDEX, setOf(EgressPayload.IMAGE_PIXELS), setOf("photo")),
                Triple(TaskType.VIDEO_INDEX, setOf(EgressPayload.VIDEO_FRAMES), setOf("video")),
                Triple(TaskType.IMAGE_INDEX, setOf(EgressPayload.USER_ANNOTATIONS), setOf("photo")),
            ),
            asked,
        )
        assertEquals(listOf("gem:a pier", "gem:image", "gem:image", "gem:Grandma's house"), sent)
    }

    @Test
    fun `nothing is sent without the grant`() = runTest {
        allowed = false
        assertFailsWith<EgressDeniedException> { remote(testScheduler).embedder("gem")!!.image(byteArrayOf(1), "photo") }
        assertTrue(sent.isEmpty())
    }

    private fun space(id: String, spec: dev.irohaco.core.contracts.EmbeddingSpaceSpec, isDefault: Boolean = false) = EmbeddingSpace(
        id, "Model", SpaceState.READY, ImageSpaces.signature(spec), isDefault, spec.modelProfileId, spec.dimension, spec.normalization,
        spec.queryPrefix, spec.corpusPrefix, spec.preprocessingSignature,
    )

    private val searched = mutableListOf<String>()
    private val staled = mutableListOf<String>()
    private val local = space("local", ImageSpaces.LOCAL.copy(modelDigest = "d"), isDefault = true)
    private val remoteSpace = space("remote", RemoteEmbedding.spec(gemini))
    private val vectors = object : VectorSearchStore {
        override fun spaces() = MutableStateFlow(listOf(local, remoteSpace))
        override suspend fun space(spaceId: String) = listOf(local, remoteSpace).firstOrNull { it.spaceId == spaceId }
        override suspend fun setDefault(spaceId: String) = false
        override suspend fun markStale(spaceIds: List<String>) {
            staled += spaceIds
        }
        override suspend fun nearest(spaceId: String, query: FloatArray, limit: Int) = listOf(VectorHit("x", null, 0.5f)).also { searched += spaceId }
        override suspend fun coverage(spaceId: String) = 1f
        override suspend fun vectorOf(spaceId: String, assetId: String): FloatArray? = null
    }
    private val localQueries = mutableListOf<String>()

    @Test
    fun `a remote space is queried by its own model, the local one by the local model`() = runTest {
        val search = SearchByMeaning(vectors, { localQueries += it; floatArrayOf(1f) }, modelDigest = "d", remote = remote(testScheduler))
        assertEquals(listOf("local", "remote"), search.compare("海").map { it.space.spaceId })
        assertEquals(listOf(ImageSpaces.LOCAL.queryPrefix + "海"), localQueries)
        assertEquals(listOf("gem:海"), sent)
        search.retireOldSpaces()
        assertEquals(emptyList(), staled)
    }

    @Test
    fun `a failing remote model leaves its space out, and a removed one retires it`() = runTest {
        val search = SearchByMeaning(vectors, { floatArrayOf(1f) }, modelDigest = "d", remote = remote(testScheduler))
        failure = RemoteFailure.Unauthorized
        assertEquals(listOf("local"), search.compare("海").map { it.space.spaceId })
        models.value = emptyList()
        assertNull(search("海", spaceId = "remote"))
        search.retireOldSpaces()
        assertEquals(listOf("remote"), staled)
    }
}
