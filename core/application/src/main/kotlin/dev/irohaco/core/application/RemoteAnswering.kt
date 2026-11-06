package dev.irohaco.core.application

import dev.irohaco.core.contracts.AnswerSession
import dev.irohaco.core.contracts.ConnectionNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import dev.irohaco.core.contracts.CredentialStore
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.EgressPolicy
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.ModelParameters
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteModelStore
import dev.irohaco.core.contracts.RemoteSessions
import dev.irohaco.core.contracts.TaskType

/** Remote models that can answer questions about photos, and the one session per question each opens (ADR-0032). */
class RemoteAnswering(
    private val store: RemoteModelStore,
    private val credentials: CredentialStore,
    private val sessions: RemoteSessions,
    val policy: EgressPolicy,
    private val names: ConnectionNames? = null,
    private val parameters: ModelParameters? = null,
) {
    val models = store.models

    /** The names the user gave the services; a change shows at once where they are listed. */
    val serviceNames: Flow<Map<String, String>> = names?.names ?: flowOf(emptyMap())

    /** The model [id] names, when it is remote and its test found all that answering needs. */
    suspend fun model(id: String): RemoteModel? = store.model(id)?.takeIf { it.capabilities.containsAll(NEEDED) }

    suspend fun connection(model: RemoteModel): RemoteConnection? =
        store.connection(model.connectionId)?.let { it.copy(name = serviceNames.first()[it.id]) }

    /** A session whose every request is checked for exactly [payloads] and [assetIds] first (ARCHITECTURE §9.3). */
    suspend fun open(model: RemoteModel, payloads: Set<EgressPayload>, assetIds: Set<String>): AnswerSession {
        val connection = checkNotNull(store.connection(model.connectionId)) { "The connection is gone" }
        val key = checkNotNull(store.credentialRef(connection.id)?.let(credentials::read)) { "The API key is gone" }
        val values = parameters?.parameters?.first()?.get(model.profileId).orEmpty()
        return sessions.open(connection, key, model.copy(parameters = values)) { policy.authorize(TaskType.RAG, model.profileId, payloads, assetIds) }
    }

    companion object {
        /** RAG sends photos with the question and reads text back (ADR-0032). */
        val NEEDED = setOf(ModelCapability.INPUT_TEXT, ModelCapability.INPUT_IMAGE, ModelCapability.OUTPUT_TEXT)
    }
}
