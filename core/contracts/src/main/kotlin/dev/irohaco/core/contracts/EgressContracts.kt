package dev.irohaco.core.contracts

import kotlinx.coroutines.flow.Flow
import java.net.URI

/** What a model is used for; the codes `egress_grants` and `task_model_assignments` store (ARCHITECTURE §9.2). */
enum class TaskType { SEARCH_QUERY, IMAGE_INDEX, VIDEO_INDEX, CAPTION, RAG, OCR_ASSIST }

/** A kind of content that could leave the device (ARCHITECTURE §9.2). */
enum class EgressPayload { QUERY_TEXT, IMAGE_PIXELS, VIDEO_FRAMES, VIDEO_BYTES, OCR_TEXT, USER_ANNOTATIONS, GPS_EXIF, PLACE_NAME }

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

/** A connection to a model outside the device; the local runtime is not one. [name] is what the user calls the service. */
data class RemoteConnection(val id: String, val protocol: RemoteProtocol, val baseUrl: String, val name: String? = null) {
    /** What the screens call the service: the user's name for it, else the company a well-known address belongs to, else its host. */
    val serviceName: String get() = name?.takeIf { it.isNotBlank() } ?: suggestedName(baseUrl)

    companion object {
        fun suggestedName(baseUrl: String): String {
            val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase() ?: return baseUrl.trim()
            return KNOWN.entries.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }?.value ?: host
        }

        private val KNOWN = mapOf("anthropic.com" to "Anthropic", "openai.com" to "OpenAI", "googleapis.com" to "Google")
    }
}

/**
 * A request field [protocol]s define for how a model thinks and how long it answers, as its API documents it: [path] is
 * dotted (`reasoning.effort` is `{"reasoning": {"effort": …}}`), [number] fields are sent as integers, and [values]
 * are the ones the API documents for a choice; a model may take only some of them, and the user may type another.
 */
data class GenerationParameter(val path: String, val number: Boolean = false, val values: List<String> = emptyList()) {
    companion object {
        private val OPENAI_EFFORT = listOf("none", "minimal", "low", "medium", "high", "xhigh")

        fun of(protocol: RemoteProtocol): List<GenerationParameter> = when (protocol) {
            RemoteProtocol.OPENAI_CHAT -> listOf(
                GenerationParameter("reasoning_effort", values = OPENAI_EFFORT),
                GenerationParameter("max_completion_tokens", number = true),
            )
            RemoteProtocol.OPENAI_RESPONSES -> listOf(
                GenerationParameter("reasoning.effort", values = OPENAI_EFFORT),
                GenerationParameter("max_output_tokens", number = true),
            )
            RemoteProtocol.ANTHROPIC_MESSAGES -> listOf(
                GenerationParameter("thinking.type", values = listOf("disabled", "enabled", "adaptive")),
                GenerationParameter("thinking.budget_tokens", number = true),
                GenerationParameter("output_config.effort", values = listOf("low", "medium", "high", "xhigh", "max")),
                // Required by the API; the app sends 1,024 unless set, and thinking counts against it.
                GenerationParameter("max_tokens", number = true),
            )
            else -> emptyList()
        }
    }
}

/**
 * What a public list of models (models.dev) says one model takes: the effort values, the least thinking budget and the
 * longest answer in tokens; null where it says nothing. It only fills in choices; the service has the last word.
 */
data class ModelOptions(val efforts: List<String>? = null, val minBudget: Int? = null, val maxOutput: Int? = null)

/** Looks a model up in a public list of models; null when it is not listed. Throws [RemoteCallException] when the list cannot be read. */
fun interface ModelInfo {
    suspend fun options(baseUrl: String, modelId: String): ModelOptions?
}

/** What a model is for, as a list of models says: answering in text, making vectors, or something else (pictures, speech). */
enum class ModelKind { CHAT, EMBEDDING, OTHER }

/** A model a service or models.dev lists, offered when the user adds one; null where nothing says. */
data class ListedModel(val id: String, val name: String? = null, val kind: ModelKind? = null, val readsImages: Boolean? = null)

/**
 * The models a connection offers, from its own list of models ([endpoint], or the protocol's usual one under the base URL)
 * and from models.dev. Only GETs go out: the service's with its key, models.dev's with nothing of the user's.
 */
fun interface ModelLister {
    suspend fun list(protocol: RemoteProtocol, baseUrl: String, apiKey: String?, endpoint: String?): List<ListedModel>
}

/** The values the user set for [GenerationParameter]s, by model profile id and path; kept apart from the models' rows. */
interface ModelParameters {
    val parameters: Flow<Map<String, Map<String, String>>>

    /** Replaces the values of [profileId]; an empty map sends none, so each model's own defaults apply. */
    suspend fun set(profileId: String, values: Map<String, String>)
}

/** The names the user gave remote connections, by connection id; kept apart from the connections' rows. */
interface ConnectionNames {
    val names: Flow<Map<String, String>>

    /** A blank or null [name] takes the name off, so the suggested one shows again. */
    suspend fun setName(id: String, name: String?)
}

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
    /** The length of its vectors, for an embedding model; its connection test measured it. */
    val dimension: Int? = null,
    /** The [GenerationParameter] values each request carries, by path; none at first. */
    val parameters: Map<String, String> = emptyMap(),
)

interface RemoteModelStore {
    val models: Flow<List<RemoteModel>>

    suspend fun model(profileId: String): RemoteModel?

    suspend fun connection(id: String): RemoteConnection?

    suspend fun credentialRef(connectionId: String): String?

    suspend fun addModel(model: RemoteModel)

    suspend fun removeModel(profileId: String)
}

/** What a connection test found: the capabilities, and for an embedding model the length of its vectors. */
data class ProbedModel(val capabilities: Set<ModelCapability>, val dimension: Int? = null)

/** Sends the app's sample text and image to a model to see what it can do; the user starts it (ADR-0032). */
fun interface CapabilityProbe {
    /** Throws [RemoteCallException] when not even a text answer comes back. */
    suspend fun probe(connection: RemoteConnection, apiKey: String, modelId: String): ProbedModel
}

/** Opens a remote model as an [AnswerSession]; [authorize] runs before each request and can stop it. */
fun interface RemoteSessions {
    fun open(connection: RemoteConnection, apiKey: String, model: RemoteModel, authorize: suspend () -> EgressDecision): AnswerSession
}

/** Opens a remote embedding model; [authorize] runs before each request with what it carries and can stop it. */
fun interface RemoteEmbeddingSessions {
    fun open(connection: RemoteConnection, apiKey: String, model: RemoteModel, authorize: suspend (EgressPayload) -> EgressDecision): EmbeddingSession
}
