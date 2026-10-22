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

    suspend fun skip(jobId: String, item: IndexItem)

    /** Puts RUNNING items of [jobId], or of every job when null, back to PENDING; those tried [maxAttempts] times fail. */
    suspend fun requeueRunning(jobId: String?, maxAttempts: Int)

    /** Fails COMMITTED items whose vector is missing; returns how many. */
    suspend fun failUnstored(jobId: String): Int

    suspend fun retryFailed(jobId: String)

    /** Pauses jobs a process that ended left RUNNING, PAUSING or VERIFYING. */
    suspend fun pauseInterrupted()

    fun progress(jobId: String): Flow<IndexJobProgress?>

    /** The job requested last, or null before the first one. */
    fun latest(): Flow<String?>

    /** File names of the job's failed items, for the failure list (IDX-002). */
    fun failedNames(jobId: String, limit: Int): Flow<List<String>>
}

/** Turns one item into its vector, outside any database transaction (INDEX_STATE_MACHINE.md §4). */
fun interface IndexItemProcessor {
    /** Throws [IndexItemSkipped] when the item no longer applies, such as a file that is gone or changed. */
    suspend fun embed(item: IndexItem): FloatArray
}

/** The item is not a failure: its file is gone, offline, or has a newer revision that a later job indexes. */
class IndexItemSkipped(message: String) : Exception(message)

/** What defines one embedding space (ARCHITECTURE §4.2); spaces with another signature never mix. */
data class EmbeddingSpaceSpec(
    val modelProfileId: String,
    val modelDigest: String,
    val dimension: Int,
    val normalization: String,
    val queryPrefix: String?,
    val corpusPrefix: String?,
    val preprocessingSignature: String,
)

/** An image as it stands now: where to read it, and the revision its vector belongs to. */
data class IndexImageSource(val contentUri: String, val revisionDigest: String)

/** Embedding spaces and image targets (M2-05). */
interface ImageIndexStore {
    /** The space with [signature], created BUILDING under [newSpaceId] if there is none yet. */
    suspend fun ensureSpace(newSpaceId: String, spec: EmbeddingSpaceSpec, signature: String): String

    suspend fun hasVectors(spaceId: String): Boolean

    /** Makes a target for every available image, in [folders] (relative paths) or everywhere when null, and returns those without a vector of their current revision in [spaceId]. */
    suspend fun planImages(spaceId: String, folders: Set<String>?): List<IndexItem>

    /** The image behind [targetId], or null when it is gone or not available. */
    suspend fun imageSource(targetId: String): IndexImageSource?
}

/** Reads an image reduced for the model; the display path stays separate (ARCHITECTURE §11). */
fun interface IndexImageReader {
    suspend fun read(contentUri: String): ByteArray
}

/** Says whether indexing must stop for now: heat, battery (AC-012, ARCHITECTURE §10.1). */
fun interface ResourcePolicy {
    fun hold(): PauseReason?
}
