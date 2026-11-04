package dev.irohaco.core.application

import dev.irohaco.core.contracts.CredentialStore
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.EgressPolicy
import dev.irohaco.core.contracts.EmbeddingSession
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.ModelNotInstalledException
import dev.irohaco.core.contracts.RemoteEmbeddingSessions
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteModelStore
import dev.irohaco.core.contracts.TaskType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remote models that index and search photos (ADR-0032). Each fills its own space, never the local
 * model's, and every request is first checked by [policy] for its task, what it carries and the file it
 * comes from. A failure is thrown, and nothing else is tried in its place (AC-009).
 */
class RemoteEmbedding(
    private val store: RemoteModelStore,
    private val credentials: CredentialStore,
    private val sessions: RemoteEmbeddingSessions,
    private val policy: EgressPolicy,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun model(profileId: String): RemoteModel? = store.model(profileId)?.takeIf { usable(it) }

    /** The space [profileId] builds, or null when it is no usable remote model. */
    suspend fun spec(profileId: String): EmbeddingSpaceSpec? = model(profileId)?.let { spec(it) }

    /**
     * The remote model that queries and fills a space of [profileId]; null for the local model, which the
     * caller runs itself. A remote model removed since then throws, so the space never gets another model's vectors.
     */
    suspend fun embedder(profileId: String): Embedder? {
        if (profileId == ImageSpaces.LOCAL.modelProfileId) return null
        return Embedder(model(profileId) ?: throw ModelNotInstalledException())
    }

    inner class Embedder internal constructor(private val model: RemoteModel) {
        suspend fun query(text: String) = send(TaskType.SEARCH_QUERY, EgressPayload.QUERY_TEXT, null) { it.embedText(text) }

        suspend fun memo(text: String, assetId: String?) = send(TaskType.IMAGE_INDEX, EgressPayload.USER_ANNOTATIONS, assetId) { it.embedText(text) }

        suspend fun image(bytes: ByteArray, assetId: String?) = send(TaskType.IMAGE_INDEX, EgressPayload.IMAGE_PIXELS, assetId) { it.embedImage(bytes) }

        suspend fun frame(bytes: ByteArray, assetId: String?) = send(TaskType.VIDEO_INDEX, EgressPayload.VIDEO_FRAMES, assetId) { it.embedImage(bytes) }

        /** The session names only text or pixels; [payload] says what that text or picture is. */
        private suspend fun send(task: TaskType, payload: EgressPayload, assetId: String?, call: (EmbeddingSession) -> FloatArray): FloatArray =
            withContext(io) {
                val connection = checkNotNull(store.connection(model.connectionId)) { "The connection is gone" }
                val key = checkNotNull(store.credentialRef(connection.id)?.let(credentials::read)) { "The API key is gone" }
                val session = sessions.open(connection, key, model) { policy.authorize(task, model.profileId, setOf(payload), setOfNotNull(assetId)) }
                session.use(call)
            }
    }

    companion object {
        /** Text and photos in one space, or text could not find photos (ADR-0032). */
        val NEEDED = setOf(ModelCapability.INPUT_TEXT, ModelCapability.INPUT_IMAGE, ModelCapability.OUTPUT_EMBEDDING)

        fun usable(model: RemoteModel) = model.capabilities.containsAll(NEEDED) && model.dimension != null

        /** No prefixes, the local model's thumbnails; the model ID stands for the file the API does not name. */
        fun spec(model: RemoteModel) = EmbeddingSpaceSpec(
            modelProfileId = model.profileId,
            modelDigest = model.modelId,
            dimension = checkNotNull(model.dimension),
            normalization = "L2",
            queryPrefix = null,
            corpusPrefix = null,
            preprocessingSignature = ImageSpaces.LOCAL.preprocessingSignature,
        )
    }
}
