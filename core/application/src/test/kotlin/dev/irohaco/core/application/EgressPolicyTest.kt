package dev.irohaco.core.application

import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressGrantStore
import dev.irohaco.core.contracts.EgressPayload.IMAGE_PIXELS
import dev.irohaco.core.contracts.EgressPayload.QUERY_TEXT
import dev.irohaco.core.contracts.EgressPayload.USER_ANNOTATIONS
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.TaskType
import dev.irohaco.core.contracts.TaskType.IMAGE_INDEX
import dev.irohaco.core.contracts.TaskType.RAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
    private val manage = ManageEgress(store)

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
}
