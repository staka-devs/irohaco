package dev.irohaco.core.application

import dev.irohaco.core.contracts.IndexItemProcessor
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexItemSkipped
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.IndexJobProgress
import dev.irohaco.core.contracts.IndexJobState
import dev.irohaco.core.contracts.IndexJobState.CANCELLED
import dev.irohaco.core.contracts.IndexJobState.FAILED
import dev.irohaco.core.contracts.IndexJobState.PARTIAL
import dev.irohaco.core.contracts.IndexJobState.PAUSED
import dev.irohaco.core.contracts.IndexJobState.PAUSING
import dev.irohaco.core.contracts.IndexJobState.PLANNED
import dev.irohaco.core.contracts.IndexJobState.RUNNING
import dev.irohaco.core.contracts.IndexJobState.SUCCEEDED
import dev.irohaco.core.contracts.IndexJobState.VERIFYING
import dev.irohaco.core.contracts.IndexJobStore
import dev.irohaco.core.contracts.ModelLoadException
import dev.irohaco.core.contracts.ModelNotInstalledException
import dev.irohaco.core.contracts.PauseReason
import dev.irohaco.core.contracts.ResourcePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs indexing jobs item by item (IDX-001, IDX-002, NFR-004, AC-012). Each committed item is a
 * checkpoint: pausing, cancelling, a stop by the system or a killed process loses at most the item in
 * progress, and a resumed job carries on with the items still pending, without duplicating any.
 */
class IndexJobs(
    private val store: IndexJobStore,
    private val processor: IndexItemProcessor,
    private val policy: ResourcePolicy,
    private val newId: () -> String,
    private val now: () -> Long,
) {
    private val recovery = Mutex()
    private var recovered = false

    private val _pauseReason = MutableStateFlow<PauseReason?>(null)

    /** Why the last run stopped by itself, or null when it did not. */
    val pauseReason: StateFlow<PauseReason?> = _pauseReason.asStateFlow()

    /** Plans a job; it runs only when [run] is called. Downloading a model alone never starts one. */
    suspend fun start(spaceId: String, kind: IndexJobKind, items: List<IndexItem>, requestedByUser: Boolean): String {
        require(kind != IndexJobKind.INITIAL || requestedByUser) { "The first index starts only when the user asks (IDX-001)" }
        return newId().also { store.create(it, spaceId, kind, requestedByUser, now(), items) }
    }

    /** Runs the job until it ends, pauses or is cancelled, and returns the state it is left in. */
    suspend fun run(jobId: String): IndexJobState {
        recover()
        if (!store.transition(jobId, setOf(PLANNED, PAUSED, PARTIAL, FAILED), RUNNING)) return checkNotNull(store.state(jobId))
        _pauseReason.value = null
        try {
            while (true) {
                policy.hold()?.let { reason ->
                    _pauseReason.value = reason
                    store.transition(jobId, ACTIVE, PAUSED)
                    return PAUSED
                }
                when (store.state(jobId)) {
                    PAUSING -> {
                        store.transition(jobId, setOf(PAUSING), PAUSED)
                        return PAUSED
                    }
                    CANCELLED -> return CANCELLED
                    else -> Unit
                }
                val item = store.nextPending(jobId) ?: break
                store.begin(jobId, item)
                try {
                    store.commit(jobId, item, newId(), processor.embed(item), now())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IndexItemSkipped) {
                    store.skip(jobId, item)
                } catch (e: ModelNotInstalledException) {
                    return failJob(jobId)
                } catch (e: ModelLoadException) {
                    return failJob(jobId)
                } catch (e: Exception) {
                    // This item alone could not be read or embedded; it stays in the failure list for a retry.
                    store.fail(jobId, item)
                }
            }
        } catch (e: CancellationException) {
            // Stopped by the system (time limit, service stopped): the item in progress goes back to the queue.
            withContext(NonCancellable) {
                store.requeueRunning(jobId, Int.MAX_VALUE)
                store.transition(jobId, ACTIVE, PAUSED)
            }
            throw e
        }
        return finish(jobId)
    }

    /** Stops after the item in progress; a job not started yet pauses at once. */
    suspend fun pause(jobId: String) {
        recover()
        if (!store.transition(jobId, setOf(RUNNING), PAUSING)) store.transition(jobId, setOf(PLANNED), PAUSED)
    }

    /** Ends the job; vectors already stored are kept. */
    suspend fun cancel(jobId: String) {
        recover()
        store.transition(jobId, setOf(PLANNED, RUNNING, PAUSING, PAUSED), CANCELLED)
    }

    /** Queues the failed items again; [run] then processes only those. */
    suspend fun retryFailed(jobId: String) = store.retryFailed(jobId)

    fun progress(jobId: String): Flow<IndexJobProgress?> = store.progress(jobId)

    /**
     * Once per process: jobs a killed process left running are paused at their last checkpoint. An
     * item that was in progress [MAX_ATTEMPTS] times fails, so one file cannot crash every run.
     */
    suspend fun recover() = recovery.withLock {
        if (recovered) return@withLock
        store.requeueRunning(null, MAX_ATTEMPTS)
        store.pauseInterrupted()
        recovered = true
    }

    /** No local model to run: the item is not at fault and goes back to the queue. */
    private suspend fun failJob(jobId: String): IndexJobState {
        store.requeueRunning(jobId, Int.MAX_VALUE)
        store.transition(jobId, ACTIVE, FAILED)
        return FAILED
    }

    private suspend fun finish(jobId: String): IndexJobState {
        if (!store.transition(jobId, setOf(RUNNING), VERIFYING)) {
            store.transition(jobId, setOf(PAUSING), PAUSED) // paused right after the last item
            return checkNotNull(store.state(jobId))
        }
        store.failUnstored(jobId)
        val end = if (checkNotNull(store.progress(jobId).first()).failed == 0) SUCCEEDED else PARTIAL
        store.transition(jobId, setOf(VERIFYING), end)
        return end
    }

    private companion object {
        val ACTIVE = setOf(RUNNING, PAUSING)
        const val MAX_ATTEMPTS = 3
    }
}
