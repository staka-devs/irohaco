package dev.irohaco.infrastructure.remote

import dev.irohaco.core.application.remoteFailure
import dev.irohaco.core.contracts.AnswerSession
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteFailure
import dev.irohaco.core.contracts.RemoteProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * A remote generation model behind the same [AnswerSession] as the local one (ADR-0032). Each question
 * first asks [authorize], and is not sent when refused; a failure is thrown as [RemoteCallException]
 * and nothing else is tried in its place (AC-009). Nothing is loaded, so warming up sends nothing.
 */
class RemoteAnswerSession(
    private val protocol: RemoteProtocol,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val streaming: Boolean,
    private val authorize: suspend (Set<EgressPayload>) -> EgressDecision,
    private val timeoutMs: Int = TIMEOUT_MS,
) : AnswerSession {
    private val format: GenerationFormat = when (protocol) {
        RemoteProtocol.OPENAI_CHAT -> ChatCompletions
        RemoteProtocol.OPENAI_RESPONSES -> Responses
        RemoteProtocol.ANTHROPIC_MESSAGES -> AnthropicMessages
        else -> throw IllegalArgumentException("$protocol does not generate text")
    }

    override suspend fun answer(instruction: String, images: List<ByteArray>, question: String, onText: (String) -> Unit): String {
        val payloads = setOf(EgressPayload.QUERY_TEXT) + if (images.isEmpty()) emptySet() else setOf(EgressPayload.IMAGE_PIXELS)
        val decision = authorize(payloads)
        if (decision is EgressDecision.Denied) throw EgressDeniedException(decision)
        val body = format.body(model, instruction, images.map(::Image), question, streaming)
        return withContext(Dispatchers.IO) {
            val connection = URL(baseUrl.trimEnd('/') + format.path).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                format.headers(apiKey).forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw RemoteCallException(remoteFailure(status, connection.getHeaderField("Retry-After"), error, listOf(apiKey)))
                }
                if (!streaming) return@withContext format.text(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
                val text = StringBuilder()
                connection.inputStream.bufferedReader().use { reader ->
                    readEvents(reader) { event, data ->
                        ensureActive()
                        format.delta(event, data)?.let {
                            text.append(it)
                            onText(text.toString())
                        }
                    }
                }
                text.toString()
            } catch (e: SocketTimeoutException) {
                throw RemoteCallException(RemoteFailure.Timeout)
            } finally {
                connection.disconnect()
            }
        }
    }

    override suspend fun warm() = Unit

    override fun close() = Unit

    private companion object {
        const val TIMEOUT_MS = 60_000
    }
}
