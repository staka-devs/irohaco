package dev.irohaco.core.application

import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AiSettings
import dev.irohaco.core.contracts.GpuCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The GPU or the CPU, checked once per model file (#169). */
class AiBackendChoiceTest {
    private val settings = object : AiSettings {
        override val backend = MutableStateFlow<AiBackend?>(null)
        override val gpuCheck = MutableStateFlow<GpuCheck?>(null)

        override suspend fun setBackend(backend: AiBackend) {
            this.backend.value = backend
        }

        override suspend fun setGpuCheck(check: GpuCheck) {
            gpuCheck.value = check
        }
    }
    private val checked = mutableListOf<String>()
    private var gpuWorks = true
    private var changes = 0
    private val choice = AiBackendChoice(settings, { path -> checked += path; gpuWorks }, { changes++ })

    @Test
    fun `the GPU runs where its check passed, checked once per model file`() = runTest {
        assertEquals(AiBackend.GPU, choice.resolve("/m1"))
        assertEquals(AiBackend.GPU, choice.resolve("/m1"))
        assertEquals(listOf("/m1"), checked)
        assertEquals(AiBackend.GPU, choice.resolve("/m2"))
        assertEquals(listOf("/m1", "/m2"), checked)
    }

    @Test
    fun `the CPU runs where the GPU failed its check, even when the GPU was chosen`() = runTest {
        gpuWorks = false
        choice.select(AiBackend.GPU)
        assertEquals(AiBackend.CPU, choice.resolve("/m1"))
        assertEquals(GpuCheck("/m1", false), settings.gpuCheck.value)
    }

    @Test
    fun `choosing the CPU skips the check and frees the loaded model`() = runTest {
        choice.select(AiBackend.CPU)
        assertEquals(AiBackend.CPU, choice.resolve("/m1"))
        assertEquals(emptyList(), checked)
        assertEquals(1, changes)
    }
}
