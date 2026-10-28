package dev.irohaco.core.application

import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AiSettings
import dev.irohaco.core.contracts.GpuCheck
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Which local backend runs the model (#169). The user picks the GPU or the CPU; until they do, the GPU
 * runs where it works. Whether it works is checked once per model file by [checkGpu], which runs the
 * model in a separate process, and the CPU is the only choice where it does not.
 */
class AiBackendChoice(
    private val settings: AiSettings,
    private val checkGpu: suspend (modelPath: String) -> Boolean,
    /** Frees the loaded model, so the next load uses the new choice. */
    private val onChange: suspend () -> Unit,
) {
    val backend: Flow<AiBackend?> = settings.backend

    val gpuCheck: Flow<GpuCheck?> = settings.gpuCheck

    suspend fun select(backend: AiBackend) {
        settings.setBackend(backend)
        onChange()
    }

    /** The backend to load [modelPath] on; the first call for a model file checks the GPU. */
    suspend fun resolve(modelPath: String): AiBackend {
        if (settings.backend.first() == AiBackend.CPU) return AiBackend.CPU
        val works = settings.gpuCheck.first()?.takeIf { it.modelPath == modelPath }?.works
            ?: checkGpu(modelPath).also { settings.setGpuCheck(GpuCheck(modelPath, it)) }
        return if (works) AiBackend.GPU else AiBackend.CPU
    }
}
