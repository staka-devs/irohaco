package dev.irohaco.infrastructure.remote

import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.EmbeddingSession
import dev.irohaco.core.contracts.RemoteProtocol
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * A remote embedding model behind the same [EmbeddingSession] as the local one (ADR-0032). Each call
 * first asks [authorize] for what it carries, a sentence or an image, and is not sent when refused; a
 * failure is thrown as [dev.irohaco.core.contracts.RemoteCallException] and nothing else is tried in its
 * place (AC-009). Vectors come back normalized, as the local model's do.
 */
class RemoteEmbeddingSession(
    protocol: RemoteProtocol,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val authorize: suspend (EgressPayload) -> EgressDecision,
    private val timeoutMs: Int = TIMEOUT_MS,
) : EmbeddingSession {
    private val format: EmbeddingFormat = when (protocol) {
        RemoteProtocol.OPENAI_EMBEDDINGS -> OpenAiEmbeddings
        RemoteProtocol.GEMINI_EMBED_CONTENT -> GeminiEmbedContent
        else -> throw IllegalArgumentException("$protocol does not embed")
    }

    /** Nothing runs on this device; the field only matters for the local model. */
    override val backend = AiBackend.CPU

    override fun embedText(text: String) = embed(EgressPayload.QUERY_TEXT, format.text(model, text))

    override fun embedImage(bytes: ByteArray) = embed(EgressPayload.IMAGE_PIXELS, format.image(model, Image(bytes)))

    // The sessions' callers are already off the main thread and do not suspend.
    private fun embed(payload: EgressPayload, body: JSONObject): FloatArray {
        val decision = runBlocking { authorize(payload) }
        if (decision is EgressDecision.Denied) throw EgressDeniedException(decision)
        val values = post(baseUrl.trimEnd('/') + format.path(model), format.headers(apiKey), body, apiKey, timeoutMs) {
            format.vector(JSONObject(it.readText()))
        }
        val vector = FloatArray(values.length()) { values.getDouble(it).toFloat() }
        val length = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
        check(length > 0f) { "The model sent back no vector" }
        return FloatArray(vector.size) { vector[it] / length }
    }

    override fun close() = Unit

    private companion object {
        const val TIMEOUT_MS = 60_000
    }
}

/** How one embedding API format asks and answers (ARCHITECTURE §9.1). */
internal interface EmbeddingFormat {
    fun path(model: String): String

    fun headers(apiKey: String): Map<String, String>

    fun text(model: String, text: String): JSONObject

    fun image(model: String, image: Image): JSONObject

    fun vector(response: JSONObject): JSONArray
}

/** OpenAI Embeddings: `POST /embeddings`, text only, the vector in `data[0].embedding`. */
internal object OpenAiEmbeddings : EmbeddingFormat {
    override fun path(model: String) = "/embeddings"

    override fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override fun text(model: String, text: String): JSONObject = JSONObject().put("model", model).put("input", text)

    // The format has no image input; the connection test then leaves INPUT_IMAGE out.
    override fun image(model: String, image: Image): JSONObject = throw UnsupportedOperationException("OpenAI Embeddings takes text only")

    override fun vector(response: JSONObject): JSONArray = response.getJSONArray("data").getJSONObject(0).getJSONArray("embedding")
}

/** Gemini embedContent: `POST /models/{model}:embedContent`, text or an inline image, the vector in `embedding.values`. */
internal object GeminiEmbedContent : EmbeddingFormat {
    override fun path(model: String) = "/models/${model.removePrefix("models/")}:embedContent"

    override fun headers(apiKey: String) = mapOf("x-goog-api-key" to apiKey)

    override fun text(model: String, text: String) = content(JSONObject().put("text", text))

    override fun image(model: String, image: Image) =
        content(JSONObject().put("inline_data", JSONObject().put("mime_type", image.mediaType).put("data", image.base64)))

    private fun content(part: JSONObject): JSONObject = JSONObject().put("content", JSONObject().put("parts", JSONArray().put(part)))

    override fun vector(response: JSONObject): JSONArray = response.getJSONObject("embedding").getJSONArray("values")
}
