package dev.irohaco.core.application

import dev.irohaco.core.contracts.ConnectionStore
import dev.irohaco.core.contracts.CredentialStore
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressGrantStore
import dev.irohaco.core.contracts.EgressPayload.IMAGE_PIXELS
import dev.irohaco.core.contracts.EgressPayload.QUERY_TEXT
import dev.irohaco.core.contracts.EgressPayload.USER_ANNOTATIONS
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.TaskType
import dev.irohaco.core.contracts.TaskType.IMAGE_INDEX
import dev.irohaco.core.contracts.TaskType.RAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Nothing leaves the device unless each kind of content has its own grant for the task (ADR-0002, API-007, AC-009). */
class EgressPolicyTest {
    private val profiles = mapOf("gpt" to "openai")
    private val store = object : EgressGrantStore {
        override val connections = emptyFlow<List<RemoteConnection>>()
        override val grants = MutableStateFlow(emptySet<EgressGrant>())
        var kept = emptySet<String>()

        override suspend fun granted(profileId: String, task: TaskType) =
            grants.value.filter { it.connectionId == profiles[profileId] && it.task == task }.map { it.payload }.toSet()

        override suspend fun blocked(assetIds: Set<String>) = assetIds intersect kept

        override suspend fun set(grant: EgressGrant, allowed: Boolean) {
            grants.value = if (allowed) grants.value + grant else grants.value - grant
        }
    }
    private val policy = GrantedEgressPolicy(store)
    private val rows = mutableMapOf<String, String>() // connection id to key reference
    private val connectionStore = object : ConnectionStore {
        var failing = false

        override suspend fun add(id: String, protocol: RemoteProtocol, baseUrl: String, credentialRef: String) {
            check(!failing)
            rows[id] = credentialRef
        }

        override suspend fun remove(id: String) = rows.remove(id)
    }
    private val keys = mutableMapOf<String, String>()
    private val credentials = object : CredentialStore {
        override fun save(secret: String) = "ref${keys.size}".also { keys[it] = secret }
        override fun read(ref: String) = keys[ref]
        override fun delete(ref: String) {
            keys.remove(ref)
        }
    }
    private val manage = ManageEgress(store, connectionStore, credentials) { "c${rows.size}" }

    @Test
    fun `with no grants nothing is sent`() = runTest {
        assertEquals(EgressDecision.Denied(setOf(QUERY_TEXT, IMAGE_PIXELS), emptySet()), policy.authorize(RAG, "gpt", setOf(QUERY_TEXT, IMAGE_PIXELS), emptySet()))
    }

    @Test
    fun `each payload needs its own grant for the task`() = runTest {
        manage.set(EgressGrant("openai", RAG, QUERY_TEXT), true)
        manage.set(EgressGrant("openai", IMAGE_INDEX, IMAGE_PIXELS), true)
        // The image grant is for indexing, so a question with images is still refused, naming what is missing.
        assertEquals(EgressDecision.Denied(setOf(IMAGE_PIXELS), emptySet()), policy.authorize(RAG, "gpt", setOf(QUERY_TEXT, IMAGE_PIXELS), emptySet()))
        manage.set(EgressGrant("openai", RAG, IMAGE_PIXELS), true)
        assertEquals(EgressDecision.Allowed, policy.authorize(RAG, "gpt", setOf(QUERY_TEXT, IMAGE_PIXELS), emptySet()))
    }

    @Test
    fun `a grant taken back refuses the next request`() = runTest {
        manage.set(EgressGrant("openai", RAG, USER_ANNOTATIONS), true)
        assertEquals(EgressDecision.Allowed, policy.authorize(RAG, "gpt", setOf(USER_ANNOTATIONS), emptySet()))
        manage.set(EgressGrant("openai", RAG, USER_ANNOTATIONS), false)
        assertEquals(EgressDecision.Denied(setOf(USER_ANNOTATIONS), emptySet()), policy.authorize(RAG, "gpt", setOf(USER_ANNOTATIONS), emptySet()))
    }

    @Test
    fun `a photo kept from sending is refused even with grants`() = runTest {
        manage.set(EgressGrant("openai", RAG, IMAGE_PIXELS), true)
        store.kept = setOf("private")
        assertEquals(EgressDecision.Denied(emptySet(), setOf("private")), policy.authorize(RAG, "gpt", setOf(IMAGE_PIXELS), setOf("private", "beach")))
    }

    @Test
    fun `another connection's grants do not count`() = runTest {
        manage.set(EgressGrant("anthropic", RAG, QUERY_TEXT), true)
        assertEquals(EgressDecision.Denied(setOf(QUERY_TEXT), emptySet()), policy.authorize(RAG, "gpt", setOf(QUERY_TEXT), emptySet()))
    }

    @Test
    fun `a connection keeps only the key's reference, and removing it removes the key`() = runTest {
        manage.add(RemoteProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1/", " secret-key ")
        assertEquals(mapOf("c0" to "ref0"), rows)
        assertEquals("secret-key", keys["ref0"])
        manage.remove("c0")
        assertTrue(rows.isEmpty() && keys.isEmpty())
    }

    @Test
    fun `a connection that cannot be saved leaves no key behind`() = runTest {
        connectionStore.failing = true
        assertFailsWith<IllegalStateException> { manage.add(RemoteProtocol.OPENAI_CHAT, "https://api.openai.com/v1", "k") }
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `only http and https URLs with a host are taken, and plain http is told apart`() = runTest {
        assertEquals(ManageEgress.UrlKind.HTTPS, ManageEgress.urlKind("https://api.openai.com/v1"))
        assertEquals(ManageEgress.UrlKind.PLAIN_HTTP, ManageEgress.urlKind("http://192.168.1.20:8080/v1"))
        assertEquals(ManageEgress.UrlKind.INVALID, ManageEgress.urlKind("ftp://example.com"))
        assertEquals(ManageEgress.UrlKind.INVALID, ManageEgress.urlKind("api.openai.com"))
        assertFailsWith<IllegalArgumentException> { manage.add(RemoteProtocol.OPENAI_CHAT, "not a url", "k") }
        assertFailsWith<IllegalArgumentException> { manage.add(RemoteProtocol.OPENAI_CHAT, "https://api.openai.com/v1", " ") }
    }
}
