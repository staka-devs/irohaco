package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.VectorHit
import dev.irohaco.core.contracts.VectorSearchStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

/**
 * A vector's file: the asset itself, the video whose frame it is, or the file a search memo is on. A
 * file's vectors count at its current revision; a memo's at the memo's, while it is searchable (AC-007).
 */
private const val VECTOR_FILES =
    """FROM embedding_vectors v JOIN embedding_targets t ON t.target_id = v.target_id
         LEFT JOIN sampled_frames f ON f.frame_id = t.frame_id
         LEFT JOIN video_segments g ON g.segment_id = f.segment_id
         LEFT JOIN media_revisions r ON r.revision_id = g.revision_id
         LEFT JOIN semantic_annotations n ON n.annotation_id = t.annotation_id
         LEFT JOIN annotation_subjects sub ON sub.subject_id = n.subject_id
         JOIN media_assets a ON a.asset_id = COALESCE(t.asset_id, r.asset_id, sub.asset_id)
       WHERE v.space_id = :spaceId AND a.tombstoned_at IS NULL AND $REVISION IS NOT NULL
         AND CASE WHEN t.annotation_id IS NULL THEN v.source_revision_digest = $REVISION
                  ELSE n.searchable = 1 AND v.source_revision_digest = $MEMO_REVISION END"""

private const val SPACES =
    """SELECT s.space_id AS spaceId, m.display_name AS modelName, s.lifecycle_state AS state, s.space_signature AS signature,
         s.query_prefix AS queryPrefix, s.model_profile_id AS modelProfileId, s.dimension AS dimension, s.normalization AS normalization,
         s.corpus_prefix AS corpusPrefix, s.preprocessing_signature AS preprocessingSignature,
         EXISTS (SELECT 1 FROM search_profile_spaces p WHERE p.search_profile_id = '$DEFAULT_PROFILE' AND p.space_id = s.space_id) AS isDefault
       FROM embedding_spaces s JOIN model_profiles m ON m.model_profile_id = s.model_profile_id"""

@Dao
interface VectorSearchDao {
    @Query("$SPACES ORDER BY s.rowid")
    fun spaces(): Flow<List<SpaceRow>>

    @Query("$SPACES WHERE s.space_id = :spaceId")
    suspend fun space(spaceId: String): SpaceRow?

    @Query("SELECT lifecycle_state FROM embedding_spaces WHERE space_id = :spaceId")
    suspend fun state(spaceId: String): String?

    @Query("INSERT OR IGNORE INTO search_profiles(search_profile_id, name, fusion_strategy) VALUES ('$DEFAULT_PROFILE', 'default', 'SINGLE_SPACE')")
    suspend fun ensureDefaultProfile()

    @Query("DELETE FROM search_profile_spaces WHERE search_profile_id = '$DEFAULT_PROFILE'")
    suspend fun clearDefault()

    @Query("INSERT INTO search_profile_spaces(search_profile_id, space_id, priority) VALUES ('$DEFAULT_PROFILE', :spaceId, 0)")
    suspend fun insertDefault(spaceId: String)

    /** Searches already running keep the space they read; new ones see the switch whole (INDEX_STATE_MACHINE.md §3). */
    @Transaction
    suspend fun setDefault(spaceId: String): Boolean {
        if (state(spaceId) != "READY") return false
        ensureDefaultProfile()
        clearDefault()
        insertDefault(spaceId)
        return true
    }

    @Query("UPDATE embedding_spaces SET lifecycle_state = 'STALE' WHERE space_id IN (:spaceIds) AND lifecycle_state NOT IN ('STALE', 'DELETING')")
    suspend fun staleSpaces(spaceIds: List<String>)

    @Query("UPDATE indexing_jobs SET state = 'CANCELLED' WHERE space_id IN (:spaceIds) AND state IN ('PLANNED', 'RUNNING', 'PAUSING', 'PAUSED', 'VERIFYING')")
    suspend fun cancelJobs(spaceIds: List<String>)

    @Query("DELETE FROM search_profile_spaces WHERE space_id IN (:spaceIds)")
    suspend fun unlinkProfiles(spaceIds: List<String>)

    @Transaction
    suspend fun markStale(spaceIds: List<String>) {
        staleSpaces(spaceIds)
        cancelJobs(spaceIds)
        unlinkProfiles(spaceIds)
    }

    @Query(
        """SELECT CAST((SELECT COUNT(DISTINCT a.asset_id) $VECTOR_FILES AND t.annotation_id IS NULL) AS REAL)
             / MAX(1, (SELECT COUNT(*) FROM media_assets a WHERE a.tombstoned_at IS NULL AND $REVISION IS NOT NULL))""",
    )
    suspend fun coverage(spaceId: String): Float
}

data class SpaceRow(
    val spaceId: String,
    val modelName: String,
    val state: String,
    val signature: String,
    val queryPrefix: String?,
    val isDefault: Boolean,
    val modelProfileId: String,
    val dimension: Int,
    val normalization: String,
    val corpusPrefix: String?,
    val preprocessingSignature: String,
)

/** Nearest-vector search over `embedding_vectors`, one space at a time (M2-08). */
class RoomVectorSearchStore(private val db: IrohacoDatabase) : VectorSearchStore {
    private val dao get() = db.vectorSearchDao()

    override fun spaces(): Flow<List<EmbeddingSpace>> = dao.spaces().map { rows -> rows.map { it.toSpace() } }

    override suspend fun space(spaceId: String) = dao.space(spaceId)?.toSpace()

    override suspend fun setDefault(spaceId: String) = dao.setDefault(spaceId)

    override suspend fun markStale(spaceIds: List<String>) {
        if (spaceIds.isNotEmpty()) dao.markStale(spaceIds)
    }

    // shortcut: compares the query with every vector of the space, read from SQLite in a stream; move to an
    // ANN index (DATABASE_DESIGN §10) when the measured search time on the reference device is too long.
    override suspend fun nearest(spaceId: String, query: FloatArray, limit: Int): List<VectorHit> = withContext(Dispatchers.IO) {
        val best = PriorityQueue<Pair<Float, VectorHit>>(compareBy { it.first })
        val values = FloatArray(query.size)
        db.openHelper.readableDatabase.query("SELECT a.asset_id, f.timestamp_ms, v.vector_blob, t.annotation_id IS NOT NULL ${VECTOR_FILES.replace(":spaceId", "?")}", arrayOf(spaceId))
            .use { c ->
                while (c.moveToNext()) {
                    val blob = c.getBlob(2)
                    if (blob.size != 4 * query.size) continue
                    ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
                    // Both sides are L2-normalized, so the dot product is the cosine similarity.
                    var score = 0f
                    for (i in values.indices) score += values[i] * query[i]
                    if (best.size < limit) {
                        best.add(score to VectorHit(c.getString(0), if (c.isNull(1)) null else c.getLong(1), c.getInt(3) == 1))
                    } else if (score > best.peek()!!.first) {
                        best.poll()
                        best.add(score to VectorHit(c.getString(0), if (c.isNull(1)) null else c.getLong(1), c.getInt(3) == 1))
                    }
                }
            }
        best.sortedByDescending { it.first }.map { it.second }
    }

    override suspend fun coverage(spaceId: String) = dao.coverage(spaceId)

    private fun SpaceRow.toSpace() =
        EmbeddingSpace(spaceId, modelName, SpaceState.valueOf(state), signature, isDefault, modelProfileId, dimension, normalization, queryPrefix, corpusPrefix, preprocessingSignature)
}
