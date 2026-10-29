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
 * An answer model with image input through LiteRT-LM (RAG-001): Gemma 4 or LFM-VL. The language model runs
 * on [AiBackend]; the vision encoder stays on the CPU, which every one of these files was checked on.
 */
class LiteRtAnswerRuntime(private val cacheDir: String) : AnswerRuntime {
    override fun load(modelPath: String, backend: AiBackend): AnswerSession {
        val text = when (backend) {
            AiBackend.GPU -> Backend.GPU()
            AiBackend.CPU -> Backend.CPU()
        }
        val engine = Engine(EngineConfig(modelPath, text, Backend.CPU(), null, null, MAX_IMAGES, cacheDir))
        try {
            engine.initialize()
        } catch (e: Throwable) {
            engine.close()
            throw e
        }
        return Session(engine)
    }

    private class Session(private val engine: Engine) : AnswerSession {
        override fun answer(instruction: String, images: List<ByteArray>, question: String): String =
            // Thinking off: LFM3 thinks by default, and its thoughts would use up the 2,048-token context.
            engine.createConversation(ConversationConfig(systemInstruction = Contents.of(instruction), thinkingConfig = ThinkingConfig(false))).use { conversation ->
                val reply = conversation.sendMessage(Contents.of(images.map { Content.ImageBytes(it) } + Content.Text(question)))
                reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            }

        override fun close() = engine.close()
    }

    private companion object {
        const val MAX_IMAGES = 4
    }
}
