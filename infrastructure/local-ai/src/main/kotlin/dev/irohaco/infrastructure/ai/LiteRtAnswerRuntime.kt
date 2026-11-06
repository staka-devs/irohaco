package dev.irohaco.infrastructure.ai

import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream

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
        val engine = Engine(EngineConfig(modelPath, engineBackend(), engineBackend(), null, MAX_TOKENS, MAX_IMAGES, cacheDir))
        try {
            engine.initialize()
        } catch (e: Throwable) {
            engine.close()
            throw e
        }
        return Session(engine)
    }

    private class Session(private val engine: Engine) : AnswerSession {
        /**
         * The thoughts arrive apart from the answer, in the message's channels; they go first, inside <think>, so the
         * reply stays one string across the answer process's boundary and AskLibrary tells the two apart.
         */
        override suspend fun answer(instruction: String, images: List<ByteArray>, question: String, onText: (String) -> Unit): String =
            conversation(instruction, THINKING).use { conversation ->
                val thought = StringBuilder()
                val reply = StringBuilder()
                fun whole() = (if (thought.isEmpty()) "" else "<think>$thought</think>") + reply
                conversation.sendMessageAsync(Contents.of(images.map { Content.ImageBytes(it) } + Content.Text(question))).collect { message ->
                    message.channels.values.forEach { thought.append(it) }
                    message.contents.contents.filterIsInstance<Content.Text>().forEach { reply.append(it.text) }
                    onText(whole())
                }
                whole()
            }

        // With an image, so the vision encoder is set up too before the first question with photos.
        override suspend fun warm() {
            conversation("Reply with OK.", ThinkingConfig(false)).use { it.sendMessage(Contents.of(Content.ImageBytes(BLANK), Content.Text("Hi"))) }
        }

        private fun conversation(instruction: String, thinking: ThinkingConfig) =
            engine.createConversation(ConversationConfig(systemInstruction = Contents.of(instruction), thinkingConfig = thinking))

        override fun close() = engine.close()
    }

    private companion object {
        const val MAX_IMAGES = 4

        // Four images take about 1,024 tokens, the instruction and notes about 400, the thoughts at most THINK_TOKENS,
        // the answer the rest; 8,192 also loads, but nothing needs it. Measured with AnswerSpeedTest (tokens, think):
        // with four images the answer starts after 14 s (Gemma 4 E2B), 21 s (LFM3-VL 2B) and 32 s (Gemma 4 E4B) on
        // the GPU. A question the photos do not fit makes a model think up to the limit, about a minute with 512
        // or 1,024 alike, so the smaller limit is kept.
        const val MAX_TOKENS = 4096
        const val THINK_TOKENS = 512

        val THINKING = ThinkingConfig(true, THINK_TOKENS)

        val BLANK: ByteArray = ByteArrayOutputStream().also {
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()
    }
}
