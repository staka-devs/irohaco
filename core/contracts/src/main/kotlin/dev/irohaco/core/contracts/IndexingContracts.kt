package dev.irohaco.core.contracts

import kotlinx.coroutines.flow.Flow

/** `indexing_jobs.state` (database/INDEX_STATE_MACHINE.md §1). */
enum class IndexJobState { PLANNED, RUNNING, PAUSING, PAUSED, VERIFYING, SUCCEEDED, PARTIAL, FAILED, CANCELLED }

/** An INITIAL job runs only when the user asked for it (IDX-001). */
enum class IndexJobKind { INITIAL, INCREMENTAL, REBUILD }

/** One target at one source revision; the pair is the item's natural key within a job (NFR-004). */
data class IndexItem(val targetId: String, val sourceRevisionDigest: String)

/** How far a job has come, counted from its items (IDX-002). */
data class IndexJobProgress(
    val state: IndexJobState,
    val pending: Int,
    val committed: Int,
    val failed: Int,
    val skipped: Int,
) {
    val total get() = pending + committed + failed + skipped
}

/** Why a job stopped at a checkpoint without the user asking (AC-012). */
enum class PauseReason { THERMAL, BATTERY }

/** Indexing jobs and their items in `indexing_jobs` and `indexing_job_items`. */
interface IndexJobStore {
    suspend fun create(jobId: String, spaceId: String, kind: IndexJobKind, requestedByUser: Boolean, requestedAt: Long, items: List<IndexItem>)

    suspend fun state(jobId: String): IndexJobState?

    /** Moves the job to [to] only from one of [from]; false when it was in another state. */
    suspend fun transition(jobId: String, from: Set<IndexJobState>, to: IndexJobState): Boolean

    /** The next item to process, or null when none is pending. */
    suspend fun nextPending(jobId: String): IndexItem?

    /** Marks the item RUNNING and counts the attempt. */
    suspend fun begin(jobId: String, item: IndexItem)

    /** Stores the vector and marks the item COMMITTED in one transaction; a vector already stored is kept. */
    suspend fun commit(jobId: String, item: IndexItem, vectorId: String, vector: FloatArray, createdAt: Long)

    suspend fun fail(jobId: String, item: IndexItem)

    /** Puts RUNNING items of [jobId], or of every job when null, back to PENDING; those tried [maxAttempts] times fail. */
    suspend fun requeueRunning(jobId: String?, maxAttempts: Int)

    /** Fails COMMITTED items whose vector is missing; returns how many. */
    suspend fun failUnstored(jobId: String): Int

    suspend fun retryFailed(jobId: String)

    /** Pauses jobs a process that ended left RUNNING, PAUSING or VERIFYING. */
    suspend fun pauseInterrupted()

    fun progress(jobId: String): Flow<IndexJobProgress?>
}

/** Turns one item into its vector, outside any database transaction (INDEX_STATE_MACHINE.md §4). */
fun interface IndexItemProcessor {
    suspend fun embed(item: IndexItem): FloatArray
}

/** Says whether indexing must stop for now: heat, battery (AC-012, ARCHITECTURE §10.1). */
fun interface ResourcePolicy {
    fun hold(): PauseReason?
}
