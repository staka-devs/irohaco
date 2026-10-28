package dev.irohaco.infrastructure.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.EmbeddingRuntime
import dev.irohaco.core.contracts.EmbeddingSession

/**
 * EmbeddingGemma 2 through LiteRT-LM (ARCHITECTURE §10, MOD-002). Text and images run on the same
 * backend; the caller adds the model's task prefix to queries. Vectors come back normalized.
 * A GPU driver can crash the process in native code, so the GPU is first tried in a separate process (#169).
 */
class LiteRtEmbeddingRuntime(private val cacheDir: String) : EmbeddingRuntime {
    override fun load(modelPath: String, backend: AiBackend): EmbeddingSession {
        fun engineBackend(): Backend = when (backend) {
            AiBackend.GPU -> Backend.GPU()
            AiBackend.CPU -> Backend.CPU()
        }
        val engine = EmbeddingEngine(EmbeddingEngineConfig(modelPath, engineBackend(), engineBackend(), engineBackend(), cacheDir))
        try {
            engine.initialize()
        } catch (e: Throwable) {
            engine.close()
            throw e
        }
        return Session(engine, backend)
    }

    private class Session(private val engine: EmbeddingEngine, override val backend: AiBackend) : EmbeddingSession {
        override fun embedText(text: String): FloatArray = engine.computeEmbedding(listOf(InputData.Text(text)), NORMALIZED).embedding

        override fun embedImage(bytes: ByteArray): FloatArray = engine.computeEmbedding(listOf(InputData.Image(bytes)), NORMALIZED).embedding

        override fun close() = engine.close()
    }

    private companion object {
        val NORMALIZED = EmbeddingOptions(true)
    }
}
