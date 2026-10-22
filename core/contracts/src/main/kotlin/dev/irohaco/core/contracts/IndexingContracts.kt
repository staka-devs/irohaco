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
    /** Items that are video frames, one per segment (IDX-004). */
    val frames: Int = 0,
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

/** A target as it stands now: where to read it, and the revision its vector belongs to. */
sealed interface IndexSource {
    val contentUri: String
    val revisionDigest: String

    data class Image(override val contentUri: String, override val revisionDigest: String) : IndexSource

    /** A video frame at [timestampMs]; the frame shown may be the nearest key frame. */
    data class Frame(override val contentUri: String, val timestampMs: Long, override val revisionDigest: String) : IndexSource
}

/**
 * How densely videos are sampled (PRD §5.2). Each quality is its own extraction profile, so a finer
 * pass adds frames next to the earlier ones.
 */
enum class VideoQuality(val intervalMs: Long, val maxFrames: Int) {
    BALANCED(10_000, 1_080),
    THOROUGH(3_000, 3_600),
}

/** Embedding spaces, and targets for images and video frames (M2-05, M2-06). */
interface MediaIndexStore {
    /** The space with [signature], created BUILDING under [newSpaceId] if there is none yet. */
    suspend fun ensureSpace(newSpaceId: String, spec: EmbeddingSpaceSpec, signature: String): String

    suspend fun hasVectors(spaceId: String): Boolean

    /**
     * Makes targets for every available image and for frames of every available video, in [folders]
     * (relative paths) or everywhere when null, and returns those without a vector of their current
     * revision in [spaceId]. Videos get segments and frames for [quality] first.
     */
    suspend fun plan(spaceId: String, folders: Set<String>?, quality: VideoQuality, now: Long): List<IndexItem>

    /** What [targetId] reads from, or null when it is gone or not available. */
    suspend fun source(targetId: String): IndexSource?

    /** Videos whose frames, in any quality, stop before the video ends (PRD §5.2 low coverage). */
    fun lowCoverageVideos(): Flow<Int>
}

/** Reads images and video frames reduced for the model; the display path stays separate (ARCHITECTURE §11). */
interface IndexImageReader {
    suspend fun read(contentUri: String): ByteArray

    suspend fun readFrame(contentUri: String, timestampMs: Long): ByteArray
}

/** Reads the text in an image on this device; nothing is sent out (IDX-003, M2-07). */
interface TextRecognizer {
    /** Stored with each result, so results of another engine can be told apart and redone. */
    val engineRevision: String

    /** The text found in [image] (JPEG bytes), lines joined by newlines; empty when there is none. */
    suspend fun recognize(image: ByteArray): String
}

/** A file whose text matched, with the frame's time when the text came from a video. */
data class TextMatch(val assetId: String, val timestampMs: Long?, val text: String)

/** OCR results in `ocr_observations`, and a full-text index over them that can be rebuilt (M2-07). */
interface TextIndexStore {
    /** Keeps [text] as the target's OCR at [revisionDigest], replacing what an older revision left. */
    suspend fun recordOcr(targetId: String, revisionDigest: String, text: String, engineRevision: String)

    /** Files whose OCR contains [query], available ones only. */
    suspend fun search(query: String, limit: Int): List<TextMatch>
}

/** Says whether indexing must stop for now: heat, battery (AC-012, ARCHITECTURE §10.1). */
fun interface ResourcePolicy {
    fun hold(): PauseReason?
}
