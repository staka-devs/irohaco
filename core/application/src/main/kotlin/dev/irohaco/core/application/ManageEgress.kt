package dev.irohaco.core.application

import dev.irohaco.core.contracts.CapabilityProbe
import dev.irohaco.core.contracts.ConnectionStore
import dev.irohaco.core.contracts.CredentialStore
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressGrantStore
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.EgressPolicy
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteModelStore
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.TaskType
import kotlinx.coroutines.flow.Flow
import java.net.URI

/**
 * Sending is allowed only when every payload has its own grant for the task and no asset is kept back
 * (ADR-0002, API-007). It reads the store on each request, so a grant taken back stops the next one.
 */
class GrantedEgressPolicy(private val store: EgressGrantStore) : EgressPolicy {
    override suspend fun authorize(task: TaskType, profileId: String, payloads: Set<EgressPayload>, assetIds: Set<String>): EgressDecision {
        require(payloads.isNotEmpty()) { "A request always sends something; name what" }
        val ungranted = payloads -store.granted(profileId, task)
        val blocked = if (assetIds.isEmpty()) emptySet() else store.blocked(assetIds)
        return if (ungranted.isEmpty() && blocked.isEmpty()) EgressDecision.Allowed else EgressDecision.Denied(ungranted, blocked)
    }
}

/**
 * The settings' list of remote connections and what each may get; everything starts off. A new
 * connection gets no grant, so adding one sends nothing (PRD §10).
 */
class ManageEgress(
    private val store: EgressGrantStore,
    private val connectionStore: ConnectionStore,
    private val credentials: CredentialStore,
    private val newId: () -> String,
    private val modelStore: RemoteModelStore,
    private val probe: CapabilityProbe,
) {
    fun connections(): Flow<List<RemoteConnection>> = store.connections

    fun grants(): Flow<Set<EgressGrant>> = store.grants

    suspend fun set(grant: EgressGrant, allowed: Boolean) = store.set(grant, allowed)

    /** Keeps [apiKey] in the Keystore-backed store and the connection with only its reference; returns the connection's id. */
    suspend fun add(protocol: RemoteProtocol, baseUrl: String, apiKey: String): String {
        require(urlKind(baseUrl) != UrlKind.INVALID) { "Not an http or https URL" }
        require(apiKey.isNotBlank()) { "No API key" }
        val ref = credentials.save(apiKey.trim())
        val id = newId()
        try {
            connectionStore.add(id, protocol, baseUrl.trim().trimEnd('/'), ref)
        } catch (e: Exception) {
            credentials.delete(ref) // no key left behind without its connection
            throw e
        }
        return id
    }

    suspend fun remove(id: String) {
        connectionStore.remove(id)?.let(credentials::delete)
    }

    fun models(): Flow<List<RemoteModel>> = modelStore.models

    /**
     * Runs the connection test on [modelId] and keeps the model with what the test confirmed (ADR-0032).
     * The test failing throws, and nothing is kept.
     */
    suspend fun addModel(connectionId: String, modelId: String, displayName: String): Set<ModelCapability> {
        require(modelId.isNotBlank()) { "No model ID" }
        val connection = checkNotNull(modelStore.connection(connectionId)) { "No such connection" }
        val key = checkNotNull(modelStore.credentialRef(connectionId)?.let(credentials::read)) { "The API key is gone" }
        val found = probe.probe(connection, key, modelId.trim())
        modelStore.addModel(RemoteModel(newId(), connectionId, modelId.trim(), displayName.trim().ifEmpty { modelId.trim() }, found.capabilities, found.dimension))
        return found.capabilities
    }

    suspend fun removeModel(profileId: String) = modelStore.removeModel(profileId)

    enum class UrlKind { HTTPS, PLAIN_HTTP, INVALID }

    companion object {
        /** Plain http sends the key and the photos unencrypted, so the screen warns before adding it (PRD §10). */
        fun urlKind(url: String): UrlKind {
            val uri = runCatching { URI(url.trim()) }.getOrNull()?.takeUnless { it.host.isNullOrEmpty() } ?: return UrlKind.INVALID
            return when (uri.scheme?.lowercase()) {
                "https" -> UrlKind.HTTPS
                "http" -> UrlKind.PLAIN_HTTP
                else -> UrlKind.INVALID
            }
        }

        /** What each task in R0 could send; location never leaves with an image (ARCHITECTURE §9.2). */
        val offered: Map<TaskType, List<EgressPayload>> = mapOf(
            TaskType.SEARCH_QUERY to listOf(EgressPayload.QUERY_TEXT),
            TaskType.IMAGE_INDEX to listOf(EgressPayload.IMAGE_PIXELS, EgressPayload.OCR_TEXT, EgressPayload.USER_ANNOTATIONS),
            TaskType.VIDEO_INDEX to listOf(EgressPayload.VIDEO_FRAMES),
            TaskType.RAG to listOf(
                EgressPayload.QUERY_TEXT,
                EgressPayload.IMAGE_PIXELS,
                EgressPayload.VIDEO_FRAMES,
                EgressPayload.OCR_TEXT,
                EgressPayload.USER_ANNOTATIONS,
            ),
        )
    }
}
