package dev.irohaco.core.application

import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AiPriority
import dev.irohaco.core.contracts.EmbeddingRuntime
import dev.irohaco.core.contracts.EmbeddingSession
import dev.irohaco.core.contracts.ModelLoadException
import dev.irohaco.core.contracts.ModelNotInstalledException
import dev.irohaco.core.contracts.ModelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The local model's one owner (ARCHITECTURE §10.1, MOD-003, MOD-004). */
@OptIn(ExperimentalCoroutinesApi::class)
class AiResourceManagerTest {
    private val loads = mutableListOf<AiBackend>()
    private val closed = mutableListOf<AiBackend>()
    private var failOn = emptySet<AiBackend>()
    private var linkFailOn = emptySet<AiBackend>()
    private var installed = true

    private val runtime = EmbeddingRuntime { _, backend ->
        loads += backend
        if (backend in failOn) error("$backend cannot run it")
        if (backend in linkFailOn) throw UnsatisfiedLinkError("no OpenCL for $backend")
        object : EmbeddingSession {
            override val backend = backend
            override fun embedText(text: String) = floatArrayOf(text.length.toFloat())
            override fun embedImage(bytes: ByteArray) = floatArrayOf(bytes.size.toFloat())
            override fun close() {
                closed += backend
            }
        }
    }

    private var chosen = AiBackend.GPU

    private fun TestScope.manager() = AiResourceManager(
        { if (installed) "/models/embedding.litertlm" else null },
        runtime,
        StandardTestDispatcher(testScheduler),
        backgroundScope,
        idleMs = 1_000,
        backendFor = { chosen },
    )

    @Test
    fun `loads nothing until the first request, then keeps the model`() = runTest {
        val ai = manager()
        ai.refresh()
        assertEquals(emptyList(), loads)
        assertEquals(ModelState.READY, ai.state.value)

        assertEquals(5f, ai.withEmbedding(AiPriority.USER_SEARCH) { it.embedText("hello") }[0])
        ai.withEmbedding(AiPriority.USER_SEARCH) { it.embedText("again") }
        assertEquals(listOf(AiBackend.GPU), loads)
        assertEquals(ModelState.LOADED, ai.state.value)
        assertEquals(AiBackend.GPU, ai.backend.value)
    }

    @Test
    fun `loads on the chosen backend`() = runTest {
        chosen = AiBackend.CPU
        val ai = manager()
        ai.withEmbedding(AiPriority.USER_SEARCH) { }
        assertEquals(listOf(AiBackend.CPU), loads)
        assertEquals(AiBackend.CPU, ai.backend.value)
    }

    @Test
    fun `fails without trying the other backend`() = runTest {
        failOn = setOf(AiBackend.GPU)
        val ai = manager()
        assertFailsWith<ModelLoadException> { ai.withEmbedding(AiPriority.USER_SEARCH) { } }
        assertEquals(listOf(AiBackend.GPU), loads)
        assertEquals(ModelState.FAILED, ai.state.value)
    }

    @Test
    fun `fails, not crashes, when the native library does not link`() = runTest {
        linkFailOn = setOf(AiBackend.GPU)
        val ai = manager()
        assertFailsWith<ModelLoadException> { ai.withEmbedding(AiPriority.USER_SEARCH) { } }
        assertEquals(ModelState.FAILED, ai.state.value)
    }

    @Test
    fun `says so and loads nothing when no model is installed`() = runTest {
        installed = false
        val ai = manager()
        ai.refresh()
        assertEquals(ModelState.NOT_INSTALLED, ai.state.value)
        assertFailsWith<ModelNotInstalledException> { ai.withEmbedding(AiPriority.USER_SEARCH) { } }
        assertEquals(emptyList(), loads)
    }

    @Test
    fun `a waiting search goes before waiting indexing`() = runTest {
        val ai = manager()
        val order = mutableListOf<String>()
        val running = CompletableDeferred<Unit>()
        launch { ai.withEmbedding(AiPriority.BACKGROUND_INDEX) { order += "index 1"; running.await() } }
        runCurrent()
        launch { ai.withEmbedding(AiPriority.BACKGROUND_INDEX) { order += "index 2" } }
        launch { ai.withEmbedding(AiPriority.USER_SEARCH) { order += "search" } }
        runCurrent()
        running.complete(Unit)
        runCurrent()
        assertEquals(listOf("index 1", "search", "index 2"), order)
    }

    @Test
    fun `a cancelled request leaves the queue`() = runTest {
        val ai = manager()
        val order = mutableListOf<String>()
        val running = CompletableDeferred<Unit>()
        launch { ai.withEmbedding(AiPriority.BACKGROUND_INDEX) { running.await() } }
        runCurrent()
        val cancelled = launch { ai.withEmbedding(AiPriority.USER_SEARCH) { order += "cancelled" } }
        launch { ai.withEmbedding(AiPriority.BACKGROUND_INDEX) { order += "next" } }
        runCurrent()
        cancelled.cancel()
        running.complete(Unit)
        runCurrent()
        assertEquals(listOf("next"), order)
    }

    @Test
    fun `frees the model when asked or when it sits unused, and loads it again`() = runTest {
        val ai = manager()
        ai.withEmbedding(AiPriority.USER_SEARCH) { }
        ai.release()
        runCurrent()
        assertEquals(listOf(AiBackend.GPU), closed)
        assertEquals(ModelState.READY, ai.state.value)

        ai.withEmbedding(AiPriority.USER_SEARCH) { }
        assertEquals(2, loads.size)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, closed.size)
        assertTrue(ai.backend.value == null)
    }

    @Test
    fun `a close that never returns does not hold up the next search`() = runTest {
        val stuck = CountDownLatch(1)
        var closeReturned = false
        val hanging = EmbeddingRuntime { _, backend ->
            object : EmbeddingSession {
                override val backend = backend
                override fun embedText(text: String) = floatArrayOf(1f)
                override fun embedImage(bytes: ByteArray) = floatArrayOf(1f)
                // Stands for the native close seen on a device (#215); bounded so a regression fails instead of hanging.
                override fun close() {
                    stuck.await(10, TimeUnit.SECONDS)
                    closeReturned = true
                }
            }
        }
        val ai = AiResourceManager({ "/models/embedding.litertlm" }, hanging, Dispatchers.IO, backgroundScope)
        ai.withEmbedding(AiPriority.USER_SEARCH) { }
        ai.release()
        assertEquals(1f, ai.withEmbedding(AiPriority.USER_SEARCH) { it.embedText("next") }[0])
        assertFalse(closeReturned, "the search waited for the close")
        stuck.countDown()
    }
}
