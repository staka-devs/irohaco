package dev.irohaco.infrastructure.remote

import dev.irohaco.core.contracts.AnswerSession
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteProtocol
import dev.irohaco.core.contracts.RemoteSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A remote generation model behind the same [AnswerSession] as the local one (ADR-0032). Each question
 * first asks [authorize], which knows what this question carries, and is not sent when refused; a failure is thrown as [RemoteCallException]
 * and nothing else is tried in its place (AC-009). Nothing is loaded, so warming up sends nothing.
 */
class RemoteAnswerSession(
    private val protocol: RemoteProtocol,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val streaming: Boolean,
    private val authorize: suspend () -> EgressDecision,
    private val timeoutMs: Int = TIMEOUT_MS,
) : AnswerSession {
    private val format: GenerationFormat = when (protocol) {
        RemoteProtocol.OPENAI_CHAT -> ChatCompletions
        RemoteProtocol.OPENAI_RESPONSES -> Responses
        RemoteProtocol.ANTHROPIC_MESSAGES -> AnthropicMessages
        else -> throw IllegalArgumentException("$protocol does not generate text")
    }

    // A service decides for itself whether a model thinks.
    override suspend fun answer(instruction: String, images: List<ByteArray>, question: String, think: Boolean, onText: (String) -> Unit): String {
        val decision = authorize()
        if (decision is EgressDecision.Denied) throw EgressDeniedException(decision)
        val body = format.body(model, instruction, images.map(::Image), question, streaming)
        return withContext(Dispatchers.IO) {
            post(baseUrl.trimEnd('/') + format.path, format.headers(apiKey), body, apiKey, timeoutMs) { reader ->
                if (!streaming) return@post format.text(JSONObject(reader.readText()))
                val text = StringBuilder()
                readEvents(reader) { event, data ->
                    ensureActive()
                    format.delta(event, data)?.let {
                        text.append(it)
                        onText(text.toString())
                    }
                }
                text.toString()
            }
        }
    }

    override suspend fun warm() = Unit

    override fun close() = Unit

    private companion object {
        const val TIMEOUT_MS = 60_000
    }
}

/** Streams the answer when the model's test found it can. */
object RemoteAnswerSessions : RemoteSessions {
    override fun open(connection: RemoteConnection, apiKey: String, model: RemoteModel, authorize: suspend () -> EgressDecision) =
        RemoteAnswerSession(connection.protocol, connection.baseUrl, apiKey, model.modelId, ModelCapability.STREAMING in model.capabilities, authorize)
}
