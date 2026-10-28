package dev.irohaco.core.application

import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AiPriority
import dev.irohaco.core.contracts.EmbeddingRuntime
import dev.irohaco.core.contracts.EmbeddingSession
import dev.irohaco.core.contracts.ModelLoadException
import dev.irohaco.core.contracts.ModelLocator
import dev.irohaco.core.contracts.ModelNotInstalledException
import dev.irohaco.core.contracts.ModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.PriorityQueue

/**
 * The one owner of the local embedding model (ARCHITECTURE §10.1, MOD-003, MOD-004). Nothing loads at
 * app start: the model loads on the first request, on the GPU and else on the CPU, and is freed when
 * memory runs low or it sits unused. Requests run one at a time, the highest priority first.
 */
class AiResourceManager(
    private val locator: ModelLocator,
    private val runtime: EmbeddingRuntime,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
) {
    private val _state = MutableStateFlow(ModelState.READY)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val _backend = MutableStateFlow<AiBackend?>(null)

    /** The backend of the loaded model, or null while none is loaded. */
    val backend: StateFlow<AiBackend?> = _backend.asStateFlow()

    private val gate = PriorityGate()
    private var session: EmbeddingSession? = null
    private var idleRelease: Job? = null

    /** Whether a model is installed, without loading it; cheap enough for app start. */
    suspend fun refresh() {
        if (session != null) return
        _state.value = if (locator.embeddingModel() == null) ModelState.NOT_INSTALLED else ModelState.READY
    }

    /** Runs [block] with the loaded model once this request's turn comes. */
    suspend fun <T> withEmbedding(priority: AiPriority, block: suspend (EmbeddingSession) -> T): T =
        gate.withTurn(priority) {
            idleRelease?.cancel()
            val loaded = session ?: load()
            try {
                withContext(io) { block(loaded) }
            } finally {
                idleRelease = scope.launch {
                    delay(idleMs)
                    release()
                }
            }
        }

    /** Frees the model after the running request, if any; the next request loads it again. */
    suspend fun release() {
        gate.withTurn(AiPriority.USER_SEARCH) {
            session?.let { withContext(io) { it.close() } }
            session = null
            _backend.value = null
            if (_state.value == ModelState.LOADED) _state.value = ModelState.READY
        }
    }

    private suspend fun load(): EmbeddingSession {
        val path = locator.embeddingModel() ?: run {
            _state.value = ModelState.NOT_INSTALLED
            throw ModelNotInstalledException()
        }
        _state.value = ModelState.LOADING
        var failure: Throwable? = null
        for (backend in AiBackend.entries) {
            try {
                return withContext(io) { runtime.load(path, backend) }.also {
                    session = it
                    _backend.value = it.backend
                    _state.value = ModelState.LOADED
                }
            } catch (e: CancellationException) {
                _state.value = ModelState.READY
                throw e
            } catch (e: Throwable) { // a native library that fails to link is an Error, and the CPU may still work
                failure = e // try the next local backend; never a remote one
            }
        }
        _state.value = ModelState.FAILED
        throw ModelLoadException(failure)
    }

    private companion object {
        const val IDLE_MS = 60_000L
    }
}

/**
 * One holder at a time; when it lets go, the waiting request with the highest priority goes next,
 * and among equals the one that came first. A cancelled waiter leaves the queue.
 */
internal class PriorityGate {
    private class Waiter(val priority: AiPriority, val order: Long, val turn: CompletableDeferred<Unit>)

    private val lock = Mutex()
    private val waiting = PriorityQueue<Waiter>(compareBy<Waiter>({ it.priority.ordinal }, { it.order }))
    private var busy = false
    private var nextOrder = 0L

    suspend fun <T> withTurn(priority: AiPriority, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            withContext(NonCancellable) { lock.withLock { handOff() } }
        }
    }

    private suspend fun acquire(priority: AiPriority) {
        val waiter = lock.withLock {
            if (!busy) {
                busy = true
                return
            }
            Waiter(priority, nextOrder++, CompletableDeferred()).also { waiting.add(it) }
        }
        try {
            waiter.turn.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                lock.withLock {
                    // Already handed the turn while being cancelled: pass it on instead of keeping it.
                    if (!waiting.remove(waiter)) handOff()
                }
            }
            throw e
        }
    }

    /** Called with [lock] held. */
    private fun handOff() {
        val next = waiting.poll()
        if (next == null) busy = false else next.turn.complete(Unit)
    }
}
