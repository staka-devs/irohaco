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
    val queryPrefix: String?,
    val isDefault: Boolean,
)

/** A file that matched by meaning, with the frame's time when it came from a video. */
data class VectorHit(val assetId: String, val timestampMs: Long?)

/** Embedding spaces, the default search profile and nearest-vector search (M2-08, ADR-0003). */
interface VectorSearchStore {
    fun spaces(): Flow<List<EmbeddingSpace>>

    suspend fun space(spaceId: String): EmbeddingSpace?

    /** Makes a READY space the default for new searches, in one transaction; false for any other state. */
    suspend fun setDefault(spaceId: String): Boolean

    /**
     * The [limit] targets of [spaceId] nearest to [query], available files at their current revision
     * only, nearest first. Scores are not returned: they mean nothing across spaces (AC-004).
     */
    suspend fun nearest(spaceId: String, query: FloatArray, limit: Int): List<VectorHit>

    /** The share of available photos and videos with a vector of their current revision in [spaceId]. */
    suspend fun coverage(spaceId: String): Float
}

/** Looks files up by id for search results; files that are gone or not available are left out. */
fun interface MediaItems {
    suspend fun items(assetIds: List<String>): List<TimelineItem>
}
