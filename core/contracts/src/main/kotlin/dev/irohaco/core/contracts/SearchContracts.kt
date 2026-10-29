package dev.irohaco.core.contracts

import kotlinx.coroutines.flow.Flow

/** `embedding_spaces.lifecycle_state` (database/INDEX_STATE_MACHINE.md §2). */
enum class SpaceState { PLANNED, BUILDING, VERIFYING, READY, STALE, FAILED, DELETING }

/** One embedding space, as the search screen lists it (IDX-006). */
data class EmbeddingSpace(
    val spaceId: String,
    val modelName: String,
    val state: SpaceState,
    val signature: String,
    val isDefault: Boolean,
    /** The settings the signature was made from, all but the model file's digest. */
    val modelProfileId: String,
    val dimension: Int,
    val normalization: String,
    val queryPrefix: String?,
    val corpusPrefix: String?,
    val preprocessingSignature: String,
) {
    /** The spec this space would have with the model file of [modelDigest]. */
    fun specWith(modelDigest: String) = EmbeddingSpaceSpec(modelProfileId, modelDigest, dimension, normalization, queryPrefix, corpusPrefix, preprocessingSignature)
}

/**
 * A file that matched by meaning, with the frame's time when it came from a video; [fromMemo] when its
 * search memo matched. [similarity] is the cosine similarity to the query, comparable only within one space (AC-004).
 */
data class VectorHit(val assetId: String, val timestampMs: Long?, val similarity: Float, val fromMemo: Boolean = false)

/** Embedding spaces, the default search profile and nearest-vector search (M2-08, ADR-0003). */
interface VectorSearchStore {
    fun spaces(): Flow<List<EmbeddingSpace>>

    suspend fun space(spaceId: String): EmbeddingSpace?

    /** Makes a READY space the default for new searches, in one transaction; false for any other state. */
    suspend fun setDefault(spaceId: String): Boolean

    /**
     * Marks [spaceIds] STALE in one transaction: their model file is gone (ADR-0003). Their open jobs
     * are cancelled, so no vector of another model file lands in them, and none stays the default.
     */
    suspend fun markStale(spaceIds: List<String>)

    /**
     * The [limit] targets of [spaceId] nearest to [query], available files at their current revision
     * only, nearest first, each with its cosine similarity to [query].
     */
    suspend fun nearest(spaceId: String, query: FloatArray, limit: Int): List<VectorHit>

    /** The share of available photos and videos with a vector of their current revision in [spaceId]. */
    suspend fun coverage(spaceId: String): Float

    /** The vector [assetId] has in [spaceId] at its current revision: the photo's, or a video's first frame; null without one. */
    suspend fun vectorOf(spaceId: String, assetId: String): FloatArray?
}

/** Looks files up by id for search results; files that are gone, not available or, with [tagId], untagged are left out. */
fun interface MediaItems {
    suspend fun items(assetIds: List<String>, tagId: String?): List<TimelineItem>
}

/**
 * Asks the OS, at the moment a search or an answer is given, which of [items] the app may still read
 * (ADR-0009, INT-004); the catalog's state is only as fresh as its last sync.
 */
fun interface MediaAccessCheck {
    suspend fun readableNow(items: List<TimelineItem>): List<TimelineItem>
}
