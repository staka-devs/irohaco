package dev.irohaco.infrastructure.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AnswerRuntime
import dev.irohaco.core.contracts.AnswerSession

/**
 * An answer model with image input through LiteRT-LM (RAG-001): Gemma 4 or LFM3-VL. The language model and
 * the vision encoder run on the same [AiBackend]: on the CPU, four images took a model 11 s to read, on the
 * GPU 3 to 4 s (AnswerSpeedTest).
 */
class LiteRtAnswerRuntime(private val cacheDir: String) : AnswerRuntime {
    override fun load(modelPath: String, backend: AiBackend): AnswerSession {
        fun engineBackend(): Backend = when (backend) {
            AiBackend.GPU -> Backend.GPU()
            AiBackend.CPU -> Backend.CPU()
        }
        val engine = Engine(EngineConfig(modelPath, engineBackend(), engineBackend(), null, null, MAX_IMAGES, cacheDir))
        try {
            engine.initialize()
        } catch (e: Throwable) {
            engine.close()
            throw e
        }
        return Session(engine)
    }

    private class Session(private val engine: Engine) : AnswerSession {
        override suspend fun answer(instruction: String, images: List<ByteArray>, question: String, onText: (String) -> Unit): String =
            conversation(instruction).use { conversation ->
                val reply = StringBuilder()
                conversation.sendMessageAsync(Contents.of(images.map { Content.ImageBytes(it) } + Content.Text(question))).collect { message ->
                    message.contents.contents.filterIsInstance<Content.Text>().forEach { reply.append(it.text) }
                    onText(reply.toString())
                }
                reply.toString()
            }

        override suspend fun warm() {
            conversation("Reply with OK.").use { it.sendMessage("Hi") }
        }

        // Thinking off: LFM3 thinks by default, and its thoughts would use up the 2,048-token context.
        private fun conversation(instruction: String) =
            engine.createConversation(ConversationConfig(systemInstruction = Contents.of(instruction), thinkingConfig = ThinkingConfig(false)))

        override fun close() = engine.close()
    }

    private companion object {
        const val MAX_IMAGES = 4
    }
}
