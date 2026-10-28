package dev.irohaco.infrastructure.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.EmbeddingRuntime
import dev.irohaco.core.contracts.EmbeddingSession
import java.io.File

/**
 * EmbeddingGemma 2 through LiteRT-LM (ARCHITECTURE §10, MOD-002). Text and images run on the same
 * backend; the caller adds the model's task prefix to queries. Vectors come back normalized.
 *
 * A GPU driver can crash the process in native code, where no catch runs. A file marks the GPU as on
 * trial from its load until its first vector; if the process dies meanwhile, the file is still there
 * and later loads refuse the GPU, so the CPU takes over instead of the app crashing on every start.
 */
class LiteRtEmbeddingRuntime(private val cacheDir: String) : EmbeddingRuntime {
    // shortcut: a GPU that crashed once stays off until the app's cache is cleared; retry it on an app update
    // when a device needs the GPU back.
    private val gpuOnTrial = File(cacheDir, GPU_ON_TRIAL)

    override fun load(modelPath: String, backend: AiBackend): EmbeddingSession {
        if (backend == AiBackend.GPU) {
            check(!gpuOnTrial.exists()) { "The GPU backend crashed an earlier process" }
            gpuOnTrial.createNewFile()
        }
        fun engineBackend(): Backend = when (backend) {
            AiBackend.GPU -> Backend.GPU()
            AiBackend.CPU -> Backend.CPU()
        }
        val engine = try {
            EmbeddingEngine(EmbeddingEngineConfig(modelPath, engineBackend(), engineBackend(), engineBackend(), cacheDir)).also {
                try {
                    it.initialize()
                } catch (e: Throwable) {
                    it.close()
                    throw e
                }
            }
        } catch (e: Throwable) {
            if (backend == AiBackend.GPU) gpuOnTrial.delete() // a failure that returned is not a crash
            throw e
        }
        return Session(engine, backend, onTrial = if (backend == AiBackend.GPU) gpuOnTrial else null)
    }

    private class Session(private val engine: EmbeddingEngine, override val backend: AiBackend, private var onTrial: File?) : EmbeddingSession {
        override fun embedText(text: String): FloatArray = passed(engine.computeEmbedding(listOf(InputData.Text(text)), NORMALIZED).embedding)

        override fun embedImage(bytes: ByteArray): FloatArray = passed(engine.computeEmbedding(listOf(InputData.Image(bytes)), NORMALIZED).embedding)

        override fun close() = engine.close()

        /** The first vector out of the GPU ends its trial. */
        private fun passed(vector: FloatArray): FloatArray {
            onTrial?.let {
                it.delete()
                onTrial = null
            }
            return vector
        }
    }

    private companion object {
        val NORMALIZED = EmbeddingOptions(true)
        const val GPU_ON_TRIAL = "litertlm-gpu-on-trial"
    }
}
