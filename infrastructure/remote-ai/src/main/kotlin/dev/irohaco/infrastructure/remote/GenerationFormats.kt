package dev.irohaco.infrastructure.remote

import dev.irohaco.core.contracts.GenerationParameter
import dev.irohaco.core.contracts.RemoteProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.Base64

/** An image as sent: already shrunk and re-encoded without its metadata, so no location goes with it. */
internal class Image(val bytes: ByteArray) {
    val mediaType = if (bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
    val base64: String = Base64.getEncoder().encodeToString(bytes)
    val dataUrl get() = "data:$mediaType;base64,$base64"
}

/** How one API format asks and answers (ARCHITECTURE §9.1); one per format, as ADR-0032 decides. */
internal interface GenerationFormat {
    val path: String

    fun headers(apiKey: String): Map<String, String>

    fun body(model: String, instruction: String, images: List<Image>, question: String, stream: Boolean): JSONObject

    /** The text one streamed event adds, or null for events that add none. */
    fun delta(event: String?, data: String): String?

    /** The whole reply of an answer that was not streamed. */
    fun text(response: JSONObject): String
}

/** OpenAI Chat Completions: `POST /chat/completions`, deltas in `choices[0].delta.content`. */
internal object ChatCompletions : GenerationFormat {
    override val path = "/chat/completions"

    override fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override fun body(model: String, instruction: String, images: List<Image>, question: String, stream: Boolean) = JSONObject()
        .put("model", model)
        .put("stream", stream)
        .put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", instruction))
                .put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray(
                            images.map { JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", it.dataUrl)) } +
                                JSONObject().put("type", "text").put("text", question),
                        ),
                    ),
                ),
        )

    override fun delta(event: String?, data: String): String? {
        if (data == "[DONE]") return null
        return JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optStringOrNull("content")
    }

    override fun text(response: JSONObject): String =
        response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
}

/** OpenAI Responses: `POST /responses`, deltas in `response.output_text.delta` events. */
internal object Responses : GenerationFormat {
    override val path = "/responses"

    override fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override fun body(model: String, instruction: String, images: List<Image>, question: String, stream: Boolean) = JSONObject()
        .put("model", model)
        .put("stream", stream)
        .put("instructions", instruction)
        .put(
            "input",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray(
                        images.map { JSONObject().put("type", "input_image").put("image_url", it.dataUrl) } +
                            JSONObject().put("type", "input_text").put("text", question),
                    ),
                ),
            ),
        )

    override fun delta(event: String?, data: String): String? {
        val json = JSONObject(data)
        return if ((event ?: json.optString("type")) == "response.output_text.delta") json.optStringOrNull("delta") else null
    }

    override fun text(response: JSONObject): String {
        val output = response.getJSONArray("output")
        return (0 until output.length()).flatMap { i ->
            val content = output.getJSONObject(i).optJSONArray("content") ?: return@flatMap emptyList()
            (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "output_text" }.map { it.optString("text") }
        }.joinToString("")
    }
}

/** Anthropic Messages: `POST /messages`, deltas in `content_block_delta` events. */
internal object AnthropicMessages : GenerationFormat {
    override val path = "/messages"

    override fun headers(apiKey: String) = mapOf("x-api-key" to apiKey, "anthropic-version" to VERSION)

    override fun body(model: String, instruction: String, images: List<Image>, question: String, stream: Boolean) = JSONObject()
        .put("model", model)
        .put("stream", stream)
        .put("max_tokens", MAX_TOKENS)
        .put("system", instruction)
        .put(
            "messages",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray(
                        images.map {
                            JSONObject().put("type", "image").put(
                                "source",
                                JSONObject().put("type", "base64").put("media_type", it.mediaType).put("data", it.base64),
                            )
                        } + JSONObject().put("type", "text").put("text", question),
                    ),
                ),
            ),
        )

    override fun delta(event: String?, data: String): String? {
        val json = JSONObject(data)
        if ((event ?: json.optString("type")) != "content_block_delta") return null
        return json.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }?.optStringOrNull("text")
    }

    override fun text(response: JSONObject): String {
        val content = response.getJSONArray("content")
        return (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
    }

    private const val VERSION = "2023-06-01"
    private const val MAX_TOKENS = 1024
}

/**
 * Puts the user's [values] into a request [body]; only the paths [protocol] defines go in, so the question,
 * the photos and the model are never changed. A number that does not read as one is left out.
 */
internal fun withParameters(body: JSONObject, protocol: RemoteProtocol, values: Map<String, String>): JSONObject {
    val defined = GenerationParameter.of(protocol).associateBy { it.path }
    values.forEach { (path, value) ->
        val parameter = defined[path] ?: return@forEach
        val keys = path.split('.')
        val parent = keys.dropLast(1).fold(body) { obj, key -> obj.optJSONObject(key) ?: JSONObject().also { obj.put(key, it) } }
        parent.put(keys.last(), if (parameter.number) value.trim().toLongOrNull() ?: return@forEach else value.trim())
    }
    return body
}

/** Reads Server-Sent Events: an `event:` line names the next `data:`; a blank line ends one event. */
internal fun readEvents(reader: BufferedReader, onEvent: (event: String?, data: String) -> Unit) {
    var event: String? = null
    val data = StringBuilder()
    fun flush(): Pair<String?, String>? = if (data.isEmpty()) null else (event to data.toString()).also { event = null; data.clear() }
    while (true) {
        val line = reader.readLine() ?: break
        when {
            line.isEmpty() -> flush()?.let { (e, d) -> onEvent(e, d) }
            line.startsWith("event:") -> event = line.substringAfter(':').trim()
            line.startsWith("data:") -> data.apply { if (isNotEmpty()) append('\n') }.append(line.substringAfter(':').removePrefix(" "))
        }
    }
    flush()?.let { (e, d) -> onEvent(e, d) }
}

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)
