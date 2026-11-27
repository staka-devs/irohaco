package dev.irohaco.core.application

import dev.irohaco.core.contracts.CapabilityProbe
import dev.irohaco.core.contracts.ConnectionNames
import dev.irohaco.core.contracts.ConnectionStore
import dev.irohaco.core.contracts.CredentialStore
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressGrantStore
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.EgressPolicy
import dev.irohaco.core.contracts.ListedModel
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.ModelLister
import dev.irohaco.core.contracts.ModelInfo
import dev.irohaco.core.contracts.ModelOptions
import dev.irohaco.core.contracts.ModelParameters
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteModelStore
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.TaskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val names: ConnectionNames? = null,
    private val parameters: ModelParameters? = null,
    private val info: ModelInfo? = null,
    private val lister: ModelLister? = null,
) {
    fun connections(): Flow<List<RemoteConnection>> =
        names?.let { combine(store.connections, it.names) { list, named -> list.map { c -> c.copy(name = named[c.id]) } } } ?: store.connections

    suspend fun rename(id: String, name: String?) {
        names?.setName(id, name?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun grants(): Flow<Set<EgressGrant>> = store.grants

    suspend fun set(grant: EgressGrant, allowed: Boolean) = store.set(grant, allowed)

    /** Keeps [apiKey] in the Keystore-backed store and the connection with only its reference; returns the connection's id. */
    suspend fun add(protocol: RemoteProtocol, baseUrl: String, apiKey: String, name: String? = null): String {
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
        if (!name.isNullOrBlank()) rename(id, name)
        return id
    }

    suspend fun remove(id: String) {
        connectionStore.remove(id)?.let(credentials::delete)
        names?.setName(id, null)
    }

    fun models(): Flow<List<RemoteModel>> =
        parameters?.let { combine(modelStore.models, it.parameters) { list, set -> list.map { m -> m.copy(parameters = set[m.profileId].orEmpty()) } } }
            ?: modelStore.models

    /** Keeps the non-blank [values] for each request [profileId] sends; none leaves each model's own defaults. */
    suspend fun setParameters(profileId: String, values: Map<String, String>) {
        parameters?.set(profileId, values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() })
    }

    /** The models the connection [connectionId] offers, read with its saved key; empty when nothing lists them. */
    suspend fun listModels(connectionId: String, endpoint: String?): List<ListedModel> {
        val connection = modelStore.connection(connectionId) ?: return emptyList()
        val key = modelStore.credentialRef(connectionId)?.let(credentials::read)
        return lister?.list(connection.protocol, connection.baseUrl, key, endpoint).orEmpty()
    }

    /** The models a connection not yet added offers; without a key only models.dev can say. */
    suspend fun listModels(protocol: RemoteProtocol, baseUrl: String, apiKey: String?, endpoint: String?): List<ListedModel> =
        lister?.list(protocol, baseUrl, apiKey?.takeIf { it.isNotBlank() }, endpoint).orEmpty()

    /** What the public list of models says [model] takes; null when it is not listed or there is no list. */
    suspend fun options(model: RemoteModel): ModelOptions? {
        val connection = modelStore.connection(model.connectionId) ?: return null
        return info?.options(connection.baseUrl, model.modelId)
    }

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

    suspend fun removeModel(profileId: String) {
        modelStore.removeModel(profileId)
        parameters?.set(profileId, emptyMap())
    }

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

        /** What each task could send; a place leaves only as its town name, never as coordinates. */
        /**
         * The jobs a connection's API format can do, each with what it may be sent: an embedding format searches and
         * indexes, a chat format answers questions (RemoteEmbeddingSession, RemoteAnswerSession).
         */
        fun offeredFor(protocol: RemoteProtocol): Map<TaskType, List<EgressPayload>> = offered.filterKeys { task ->
            when (protocol) {
                RemoteProtocol.OPENAI_EMBEDDINGS, RemoteProtocol.GEMINI_EMBED_CONTENT -> task != TaskType.RAG
                else -> task == TaskType.RAG
            }
        }

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
                EgressPayload.PLACE_NAME,
            ),
        )
    }
}
