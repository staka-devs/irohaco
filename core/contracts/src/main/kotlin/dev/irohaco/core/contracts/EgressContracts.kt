package dev.irohaco.core.contracts

import kotlinx.coroutines.flow.Flow

/** What a model is used for; the codes `egress_grants` and `task_model_assignments` store (ARCHITECTURE §9.2). */
enum class TaskType { SEARCH_QUERY, IMAGE_INDEX, VIDEO_INDEX, CAPTION, RAG, OCR_ASSIST }

/** A kind of content that could leave the device (ARCHITECTURE §9.2). */
enum class EgressPayload { QUERY_TEXT, IMAGE_PIXELS, VIDEO_FRAMES, VIDEO_BYTES, OCR_TEXT, USER_ANNOTATIONS, GPS_EXIF }

/** One permission: this connection may get this payload for this task. No row means no (ADR-0002). */
data class EgressGrant(val connectionId: String, val task: TaskType, val payload: EgressPayload)

/** The API formats a remote connection can speak (ARCHITECTURE §9.1), with the URL each starts from. */
enum class RemoteProtocol(val displayName: String, val defaultBaseUrl: String) {
    OPENAI_CHAT("OpenAI Chat Completions", "https://api.openai.com/v1"),
    OPENAI_RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
    ANTHROPIC_MESSAGES("Anthropic Messages", "https://api.anthropic.com/v1"),
    OPENAI_EMBEDDINGS("OpenAI Embeddings", "https://api.openai.com/v1"),
    GEMINI_EMBED_CONTENT("Gemini embedContent", "https://generativelanguage.googleapis.com/v1beta"),
}

/** A connection to a model outside the device; the local runtime is not one. */
data class RemoteConnection(val id: String, val protocol: RemoteProtocol, val baseUrl: String)

sealed interface EgressDecision {
    data object Allowed : EgressDecision

    /** Nothing is sent, and nothing else is tried in its place (AC-009). */
    data class Denied(val ungranted: Set<EgressPayload>, val blockedAssets: Set<String>) : EgressDecision
}

/** Asked before every request to a remote model; a screen's toggle alone never allows one (ARCHITECTURE §9.3). */
interface EgressPolicy {
    suspend fun authorize(task: TaskType, profileId: String, payloads: Set<EgressPayload>, assetIds: Set<String>): EgressDecision
}

interface EgressGrantStore {
    val connections: Flow<List<RemoteConnection>>

    val grants: Flow<Set<EgressGrant>>

    /** What [profileId]'s connection may get for [task]; empty for a profile with no remote connection. */
    suspend fun granted(profileId: String, task: TaskType): Set<EgressPayload>

    /** Those of [assetIds] the user kept from being sent anywhere (`asset_privacy_rules`). */
    suspend fun blocked(assetIds: Set<String>): Set<String>

    suspend fun set(grant: EgressGrant, allowed: Boolean)
}

/** Remote connections as rows; the key itself is only ever in [CredentialStore], the row holds its reference. */
interface ConnectionStore {
    suspend fun add(id: String, protocol: RemoteProtocol, baseUrl: String, credentialRef: String)

    /** The reference of the removed connection's key; its grants go with it. */
    suspend fun remove(id: String): String?
}

/** Secrets encrypted with a key the Android Keystore holds, kept out of backups (API-008, PRD §10). */
interface CredentialStore {
    /** Stores [secret] and returns the reference the database keeps instead of it. */
    fun save(secret: String): String

    fun read(ref: String): String?

    fun delete(ref: String)
}

/** Why a remote call failed, as the screens tell the user (API-008, ARCHITECTURE §9.2). */
sealed interface RemoteFailure {
    /** 429: too many requests for now; [retryAfterSeconds] when the server said when to try again. */
    data class RateLimited(val retryAfterSeconds: Long?) : RemoteFailure

    /** 401 and 403: the key was refused. */
    data object Unauthorized : RemoteFailure

    /** The account's usage or spending limit is reached; retrying does not help. */
    data object QuotaExceeded : RemoteFailure

    data object Timeout : RemoteFailure

    /** No answer at all: no network, an unknown host, a refused connection. */
    data object Unreachable : RemoteFailure

    /** Any other status; [message] has its secrets taken out. */
    data class Failed(val status: Int, val message: String) : RemoteFailure
}

/** A remote call that failed; [failure] says why, with no key in it. */
class RemoteCallException(val failure: RemoteFailure) : java.io.IOException(failure.toString())

/** The policy refused a request, so nothing was sent and nothing else was tried (AC-009). */
class EgressDeniedException(val decision: EgressDecision.Denied) : IllegalStateException(decision.toString())

/** What a model was found to do in its connection test; only these are stored (ADR-0032). */
enum class ModelCapability { INPUT_TEXT, INPUT_IMAGE, INPUT_VIDEO, OUTPUT_TEXT, OUTPUT_EMBEDDING, STREAMING }

/** A model registered on a remote connection, with the capabilities its test confirmed. */
data class RemoteModel(
    val profileId: String,
    val connectionId: String,
    val modelId: String,
    val displayName: String,
    val capabilities: Set<ModelCapability>,
)

interface RemoteModelStore {
    val models: Flow<List<RemoteModel>>

    suspend fun model(profileId: String): RemoteModel?

    suspend fun connection(id: String): RemoteConnection?

    suspend fun credentialRef(connectionId: String): String?

    suspend fun addModel(model: RemoteModel)

    suspend fun removeModel(profileId: String)
}

/** Sends the app's sample text and image to a model to see what it can do; the user starts it (ADR-0032). */
fun interface CapabilityProbe {
    /** Throws [RemoteCallException] when not even a text answer comes back. */
    suspend fun probe(connection: RemoteConnection, apiKey: String, modelId: String): Set<ModelCapability>
}

/** Opens a remote model as an [AnswerSession]; [authorize] runs before each request and can stop it. */
fun interface RemoteSessions {
    fun open(connection: RemoteConnection, apiKey: String, model: RemoteModel, authorize: suspend () -> EgressDecision): AnswerSession
}
