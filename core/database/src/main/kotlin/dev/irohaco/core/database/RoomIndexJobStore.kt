package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.IndexJobProgress
import dev.irohaco.core.contracts.IndexJobState
import dev.irohaco.core.contracts.IndexJobStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Dao
interface IndexingDao {
    @Query(
        """INSERT INTO indexing_jobs(job_id, space_id, job_kind, state, requested_by_user, requested_at)
           VALUES (:jobId, :spaceId, :kind, 'PLANNED', :requestedByUser, :requestedAt)""",
    )
    suspend fun insertJob(jobId: String, spaceId: String, kind: String, requestedByUser: Boolean, requestedAt: Long)

    @Query(
        """INSERT OR IGNORE INTO indexing_job_items(job_id, target_id, source_revision_digest, state, attempts)
           VALUES (:jobId, :targetId, :digest, 'PENDING', 0)""",
    )
    suspend fun insertItem(jobId: String, targetId: String, digest: String)

    @Transaction
    suspend fun create(jobId: String, spaceId: String, kind: String, requestedByUser: Boolean, requestedAt: Long, items: List<IndexItem>) {
        insertJob(jobId, spaceId, kind, requestedByUser, requestedAt)
        items.forEach { insertItem(jobId, it.targetId, it.sourceRevisionDigest) }
    }

    @Query("SELECT state FROM indexing_jobs WHERE job_id = :jobId")
    suspend fun state(jobId: String): String?

    @Query("UPDATE indexing_jobs SET state = :to WHERE job_id = :jobId AND state IN (:from)")
    suspend fun transition(jobId: String, from: List<String>, to: String): Int

    @Query(
        """SELECT target_id AS targetId, source_revision_digest AS sourceRevisionDigest FROM indexing_job_items
           WHERE job_id = :jobId AND state = 'PENDING' ORDER BY rowid LIMIT 1""",
    )
    suspend fun nextPending(jobId: String): IndexItem?

    @Query(
        """UPDATE indexing_job_items SET state = :state, attempts = attempts + :attempt
           WHERE job_id = :jobId AND target_id = :targetId AND source_revision_digest = :digest""",
    )
    suspend fun setItemState(jobId: String, targetId: String, digest: String, state: String, attempt: Int = 0)

    // OR IGNORE: a vector already stored for this space, target and revision is the same result (§4 step 5).
    @Query(
        """INSERT OR IGNORE INTO embedding_vectors(vector_id, space_id, target_id, source_revision_digest, vector_blob, created_at)
           SELECT :vectorId, space_id, :targetId, :digest, :blob, :createdAt FROM indexing_jobs WHERE job_id = :jobId""",
    )
    suspend fun insertVector(jobId: String, vectorId: String, targetId: String, digest: String, blob: ByteArray, createdAt: Long)

    @Transaction
    suspend fun commit(jobId: String, item: IndexItem, vectorId: String, blob: ByteArray, createdAt: Long) {
        insertVector(jobId, vectorId, item.targetId, item.sourceRevisionDigest, blob, createdAt)
        setItemState(jobId, item.targetId, item.sourceRevisionDigest, "COMMITTED")
    }

    @Query(
        """UPDATE indexing_job_items SET state = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END
           WHERE state = 'RUNNING' AND (:jobId IS NULL OR job_id = :jobId)""",
    )
    suspend fun requeueRunning(jobId: String?, maxAttempts: Int)

    @Query(
        """UPDATE indexing_job_items SET state = 'FAILED'
           WHERE job_id = :jobId AND state = 'COMMITTED' AND NOT EXISTS (
             SELECT 1 FROM embedding_vectors v JOIN indexing_jobs j ON j.space_id = v.space_id
             WHERE j.job_id = indexing_job_items.job_id AND v.target_id = indexing_job_items.target_id
               AND v.source_revision_digest = indexing_job_items.source_revision_digest)""",
    )
    suspend fun failUnstored(jobId: String): Int

    @Query("UPDATE indexing_job_items SET state = 'PENDING' WHERE job_id = :jobId AND state = 'FAILED'")
    suspend fun retryFailed(jobId: String)

    @Query("UPDATE indexing_jobs SET state = 'PAUSED' WHERE state IN ('RUNNING', 'PAUSING', 'VERIFYING')")
    suspend fun pauseInterrupted()

    @Query(
        """SELECT j.state AS state,
             (SELECT COUNT(*) FROM indexing_job_items i WHERE i.job_id = j.job_id AND i.state IN ('PENDING', 'RUNNING')) AS pending,
             (SELECT COUNT(*) FROM indexing_job_items i WHERE i.job_id = j.job_id AND i.state = 'COMMITTED') AS committed,
             (SELECT COUNT(*) FROM indexing_job_items i WHERE i.job_id = j.job_id AND i.state = 'FAILED') AS failed,
             (SELECT COUNT(*) FROM indexing_job_items i WHERE i.job_id = j.job_id AND i.state = 'SKIPPED') AS skipped,
             (SELECT COUNT(*) FROM indexing_job_items i JOIN embedding_targets t ON t.target_id = i.target_id
              WHERE i.job_id = j.job_id AND t.frame_id IS NOT NULL) AS frames
           FROM indexing_jobs j WHERE j.job_id = :jobId""",
    )
    fun progress(jobId: String): Flow<ProgressRow?>

    @Query(
        """UPDATE embedding_spaces SET lifecycle_state = :to WHERE lifecycle_state = :from
             AND space_id = (SELECT space_id FROM indexing_jobs WHERE job_id = :jobId)""",
    )
    suspend fun moveSpace(jobId: String, from: String, to: String)

    @Query("INSERT OR IGNORE INTO search_profiles(search_profile_id, name, fusion_strategy) VALUES ('$DEFAULT_PROFILE', 'default', 'SINGLE_SPACE')")
    suspend fun ensureDefaultProfile()

    @Query(
        """INSERT INTO search_profile_spaces(search_profile_id, space_id, priority)
           SELECT '$DEFAULT_PROFILE', j.space_id, 0 FROM indexing_jobs j JOIN embedding_spaces s ON s.space_id = j.space_id
           WHERE j.job_id = :jobId AND s.lifecycle_state = 'READY'
             AND NOT EXISTS (SELECT 1 FROM search_profile_spaces WHERE search_profile_id = '$DEFAULT_PROFILE')""",
    )
    suspend fun defaultIfNone(jobId: String)

    @Transaction
    suspend fun markSpaceReady(jobId: String) {
        moveSpace(jobId, "BUILDING", "VERIFYING")
        moveSpace(jobId, "VERIFYING", "READY")
        ensureDefaultProfile()
        defaultIfNone(jobId)
    }

    @Query("SELECT job_id FROM indexing_jobs ORDER BY requested_at DESC, rowid DESC LIMIT 1")
    fun latest(): Flow<String?>

    @Query(
        """SELECT COALESCE((SELECT MIN(s.display_name) FROM media_sources s WHERE s.asset_id = t.asset_id), i.target_id)
           FROM indexing_job_items i JOIN embedding_targets t ON t.target_id = i.target_id
           WHERE i.job_id = :jobId AND i.state = 'FAILED' ORDER BY i.rowid LIMIT :limit""",
    )
    fun failedNames(jobId: String, limit: Int): Flow<List<String>>
}

/** The profile new searches use; switching it changes one row in one transaction (ADR-0003). */
internal const val DEFAULT_PROFILE = "default"

data class ProgressRow(val state: String, val pending: Int, val committed: Int, val failed: Int, val skipped: Int, val frames: Int)

/** `indexing_jobs` and `indexing_job_items`; vectors go into `embedding_vectors` as FLOAT32_LE (M2-03). */
class RoomIndexJobStore(private val db: IrohacoDatabase) : IndexJobStore {
    private val dao get() = db.indexingDao()

    override suspend fun create(jobId: String, spaceId: String, kind: IndexJobKind, requestedByUser: Boolean, requestedAt: Long, items: List<IndexItem>) =
        dao.create(jobId, spaceId, kind.name, requestedByUser, requestedAt, items)

    override suspend fun state(jobId: String) = dao.state(jobId)?.let(IndexJobState::valueOf)

    override suspend fun transition(jobId: String, from: Set<IndexJobState>, to: IndexJobState) =
        dao.transition(jobId, from.map { it.name }, to.name) > 0

    override suspend fun nextPending(jobId: String) = dao.nextPending(jobId)

    override suspend fun begin(jobId: String, item: IndexItem) =
        dao.setItemState(jobId, item.targetId, item.sourceRevisionDigest, "RUNNING", attempt = 1)

    override suspend fun commit(jobId: String, item: IndexItem, vectorId: String, vector: FloatArray, createdAt: Long) {
        val blob = vector.toBlob()
        dao.commit(jobId, item, vectorId, blob, createdAt)
    }

    override suspend fun fail(jobId: String, item: IndexItem) = dao.setItemState(jobId, item.targetId, item.sourceRevisionDigest, "FAILED")

    override suspend fun skip(jobId: String, item: IndexItem) = dao.setItemState(jobId, item.targetId, item.sourceRevisionDigest, "SKIPPED")

    override suspend fun requeueRunning(jobId: String?, maxAttempts: Int) = dao.requeueRunning(jobId, maxAttempts)

    override suspend fun failUnstored(jobId: String) = dao.failUnstored(jobId)

    override suspend fun retryFailed(jobId: String) = dao.retryFailed(jobId)

    override suspend fun pauseInterrupted() = dao.pauseInterrupted()

    override suspend fun markSpaceReady(jobId: String) = dao.markSpaceReady(jobId)

    override fun progress(jobId: String): Flow<IndexJobProgress?> = dao.progress(jobId).map { row ->
        row?.let { IndexJobProgress(IndexJobState.valueOf(it.state), it.pending, it.committed, it.failed, it.skipped, it.frames) }
    }

    override fun latest() = dao.latest()

    override fun failedNames(jobId: String, limit: Int) = dao.failedNames(jobId, limit)
}

/** FLOAT32_LE, the only `value_format` of `embedding_spaces`. */
internal fun FloatArray.toBlob(): ByteArray = ByteBuffer.allocate(4 * size).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(this@toBlob) }.array()
