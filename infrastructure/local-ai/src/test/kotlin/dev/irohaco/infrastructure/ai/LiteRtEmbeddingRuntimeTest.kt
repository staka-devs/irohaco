package dev.irohaco.infrastructure.ai

import dev.irohaco.core.contracts.AiBackend
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A GPU that crashed the process keeps off, so the CPU takes over instead of the app crashing again. */
class LiteRtEmbeddingRuntimeTest {
    @get:Rule
    val cache = TemporaryFolder()

    @Test
    fun `a GPU left on trial by a dead process is refused before it loads`() {
        File(cache.root, "litertlm-gpu-on-trial").createNewFile()
        val e = assertFailsWith<IllegalStateException> { LiteRtEmbeddingRuntime(cache.root.path).load("/no/model", AiBackend.GPU) }
        assertTrue("crashed" in e.message.orEmpty())
    }
}
