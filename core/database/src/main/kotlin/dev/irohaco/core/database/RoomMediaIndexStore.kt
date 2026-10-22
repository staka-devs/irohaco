package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexSource
import dev.irohaco.core.contracts.MediaIndexStore
import dev.irohaco.core.contracts.VideoQuality
import kotlinx.coroutines.flow.Flow

// shortcut: a revision is the file's size and modified time until catalog sync records media_revisions digests;
// a pixel edit that keeps both is missed until then.
internal const val REVISION =
    """(SELECT MIN(s.file_size_bytes || ':' || COALESCE(s.observed_modified_at, 0)) FROM media_sources s
        WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE')"""

private const val IN_FOLDERS =
    """EXISTS (SELECT 1 FROM media_sources s WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE'
               AND (:everywhere OR COALESCE(s.relative_path, '') IN (:folders)))"""

@Dao
interface MediaIndexDao {
    @Query(
        """INSERT OR IGNORE INTO embedding_spaces(space_id, model_profile_id, dimension, normalization, query_prefix,
             corpus_prefix, preprocessing_signature, space_signature, lifecycle_state)
           VALUES (:spaceId, :modelProfileId, :dimension, :normalization, :queryPrefix, :corpusPrefix, :preprocessing, :signature, 'BUILDING')""",
    )
    suspend fun insertSpace(
        spaceId: String,
        modelProfileId: String,
        dimension: Int,
        normalization: String,
        queryPrefix: String?,
        corpusPrefix: String?,
        preprocessing: String,
        signature: String,
    )

    @Query("SELECT space_id FROM embedding_spaces WHERE space_signature = :signature")
    suspend fun spaceId(signature: String): String

    @Transaction
    suspend fun ensureSpace(newSpaceId: String, spec: EmbeddingSpaceSpec, signature: String): String {
        insertSpace(newSpaceId, spec.modelProfileId, spec.dimension, spec.normalization, spec.queryPrefix, spec.corpusPrefix, spec.preprocessingSignature, signature)
        return spaceId(signature)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM embedding_vectors WHERE space_id = :spaceId)")
    suspend fun hasVectors(spaceId: String): Boolean

    @Query(
        """INSERT OR IGNORE INTO embedding_targets(target_id, asset_id)
           SELECT 'asset:' || a.asset_id, a.asset_id FROM media_assets a
           WHERE a.media_kind = 'IMAGE' AND a.tombstoned_at IS NULL AND $IN_FOLDERS""",
    )
    suspend fun insertImageTargets(everywhere: Boolean, folders: List<String>)

    @Query(
        """SELECT targetId, sourceRevisionDigest FROM (
             SELECT t.target_id AS targetId, $REVISION AS sourceRevisionDigest
             FROM embedding_targets t JOIN media_assets a ON a.asset_id = t.asset_id
             WHERE a.media_kind = 'IMAGE' AND a.tombstoned_at IS NULL AND $IN_FOLDERS)
           WHERE NOT EXISTS (SELECT 1 FROM embedding_vectors v WHERE v.space_id = :spaceId
                             AND v.target_id = targetId AND v.source_revision_digest = sourceRevisionDigest)""",
    )
    suspend fun unindexedImages(spaceId: String, everywhere: Boolean, folders: List<String>): List<IndexItem>

    /** Videos with a known length whose current revision has no segments for [profileId] yet. */
    @Query(
        """SELECT assetId, digest, durationMs FROM (
             SELECT a.asset_id AS assetId, $REVISION AS digest,
               (SELECT MAX(s.observed_duration_ms) FROM media_sources s WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE') AS durationMs
             FROM media_assets a WHERE a.media_kind = 'VIDEO' AND a.tombstoned_at IS NULL AND $IN_FOLDERS)
           WHERE durationMs > 0 AND NOT EXISTS (
             SELECT 1 FROM video_segments g WHERE g.revision_id = assetId || ':' || digest AND g.extraction_profile_id = :profileId)""",
    )
    suspend fun unsegmentedVideos(profileId: String, everywhere: Boolean, folders: List<String>): List<VideoRow>

    @Query(
        """INSERT OR IGNORE INTO frame_extraction_profiles(extraction_profile_id, strategy, interval_ms, max_frames, profile_digest)
           VALUES (:profileId, 'FIXED_INTERVAL', :intervalMs, :maxFrames, :profileId)""",
    )
    suspend fun insertProfile(profileId: String, intervalMs: Long, maxFrames: Int)

    @Query(
        """INSERT OR IGNORE INTO media_revisions(revision_id, asset_id, file_digest, observed_at, duration_ms)
           VALUES (:revisionId, :assetId, :digest, :observedAt, :durationMs)""",
    )
    suspend fun insertRevision(revisionId: String, assetId: String, digest: String, observedAt: Long, durationMs: Long)

    @Query("INSERT OR IGNORE INTO video_segments(segment_id, revision_id, extraction_profile_id, start_ms, end_ms) VALUES (:segmentId, :revisionId, :profileId, :startMs, :endMs)")
    suspend fun insertSegment(segmentId: String, revisionId: String, profileId: String, startMs: Long, endMs: Long)

    // The frame's pixels are not read while planning; the file revision and the time stand for them.
    @Query("INSERT OR IGNORE INTO sampled_frames(frame_id, segment_id, timestamp_ms, frame_digest) VALUES (:frameId, :segmentId, :timestampMs, :digest)")
    suspend fun insertFrame(frameId: String, segmentId: String, timestampMs: Long, digest: String)

    @Query("INSERT OR IGNORE INTO embedding_targets(target_id, frame_id) VALUES ('frame:' || :frameId, :frameId)")
    suspend fun insertFrameTarget(frameId: String)

    @Query(
        """SELECT targetId, sourceRevisionDigest FROM (
             SELECT t.target_id AS targetId, $REVISION AS sourceRevisionDigest, r.file_digest AS planned
             FROM embedding_targets t JOIN sampled_frames f ON f.frame_id = t.frame_id
               JOIN video_segments g ON g.segment_id = f.segment_id
               JOIN media_revisions r ON r.revision_id = g.revision_id
               JOIN media_assets a ON a.asset_id = r.asset_id
             WHERE g.extraction_profile_id = :profileId AND a.tombstoned_at IS NULL AND $IN_FOLDERS
             ORDER BY r.asset_id, f.timestamp_ms)
           WHERE planned = sourceRevisionDigest AND NOT EXISTS (SELECT 1 FROM embedding_vectors v WHERE v.space_id = :spaceId
                             AND v.target_id = targetId AND v.source_revision_digest = sourceRevisionDigest)""",
    )
    suspend fun unindexedFrames(spaceId: String, profileId: String, everywhere: Boolean, folders: List<String>): List<IndexItem>

    @Transaction
    suspend fun plan(spaceId: String, everywhere: Boolean, folders: List<String>, quality: VideoQuality, now: Long): List<IndexItem> {
        insertImageTargets(everywhere, folders)
        val profileId = quality.profileId
        insertProfile(profileId, quality.intervalMs, quality.maxFrames)
        for (video in unsegmentedVideos(profileId, everywhere, folders)) {
            val revisionId = "${video.assetId}:${video.digest}"
            insertRevision(revisionId, video.assetId, video.digest, now, video.durationMs)
            val covered = minOf(video.durationMs, quality.intervalMs * quality.maxFrames)
            for (start in 0 until covered step quality.intervalMs) {
                val end = minOf(start + quality.intervalMs, video.durationMs)
                val segmentId = "$revisionId:$profileId:$start"
                val frameId = "$segmentId@${start + (end - start) / 2}"
                insertSegment(segmentId, revisionId, profileId, start, end)
                insertFrame(frameId, segmentId, start + (end - start) / 2, video.digest)
                insertFrameTarget(frameId)
            }
        }
        return unindexedImages(spaceId, everywhere, folders) + unindexedFrames(spaceId, profileId, everywhere, folders)
    }

    @Query(
        """SELECT (SELECT MIN(s.content_uri) FROM media_sources s WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE') AS contentUri,
             $REVISION AS revisionDigest
           FROM embedding_targets t JOIN media_assets a ON a.asset_id = t.asset_id
           WHERE t.target_id = :targetId AND a.tombstoned_at IS NULL""",
    )
    suspend fun imageSource(targetId: String): IndexSourceRow?

    @Query(
        """SELECT (SELECT MIN(s.content_uri) FROM media_sources s WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE') AS contentUri,
             $REVISION AS revisionDigest, f.timestamp_ms AS timestampMs
           FROM embedding_targets t JOIN sampled_frames f ON f.frame_id = t.frame_id
             JOIN video_segments g ON g.segment_id = f.segment_id
             JOIN media_revisions r ON r.revision_id = g.revision_id
             JOIN media_assets a ON a.asset_id = r.asset_id
           WHERE t.target_id = :targetId AND a.tombstoned_at IS NULL""",
    )
    suspend fun frameSource(targetId: String): FrameSourceRow?

    @Query(
        """SELECT COUNT(*) FROM (
             SELECT r.revision_id FROM video_segments g JOIN media_revisions r ON r.revision_id = g.revision_id
               JOIN media_assets a ON a.asset_id = r.asset_id
             WHERE a.tombstoned_at IS NULL AND r.file_digest = $REVISION
             GROUP BY r.revision_id HAVING MAX(g.end_ms) < MAX(r.duration_ms))""",
    )
    fun lowCoverageVideos(): Flow<Int>
}

data class VideoRow(val assetId: String, val digest: String, val durationMs: Long)

data class IndexSourceRow(val contentUri: String?, val revisionDigest: String?)

data class FrameSourceRow(val contentUri: String?, val revisionDigest: String?, val timestampMs: Long)

private val VideoQuality.profileId get() = "fixed-${intervalMs}ms-max$maxFrames"

/** Embedding spaces, one target per image asset, and segments and frames of videos (M2-05, M2-06). */
class RoomMediaIndexStore(private val db: IrohacoDatabase) : MediaIndexStore {
    private val dao get() = db.mediaIndexDao()

    override suspend fun ensureSpace(newSpaceId: String, spec: EmbeddingSpaceSpec, signature: String) = dao.ensureSpace(newSpaceId, spec, signature)

    override suspend fun hasVectors(spaceId: String) = dao.hasVectors(spaceId)

    override suspend fun plan(spaceId: String, folders: Set<String>?, quality: VideoQuality, now: Long) =
        dao.plan(spaceId, folders == null, folders.orEmpty().toList(), quality, now)

    override suspend fun source(targetId: String): IndexSource? = if (targetId.startsWith("frame:")) {
        dao.frameSource(targetId)?.let { IndexSource.Frame(it.contentUri ?: return null, it.timestampMs, it.revisionDigest ?: return null) }
    } else {
        dao.imageSource(targetId)?.let { IndexSource.Image(it.contentUri ?: return null, it.revisionDigest ?: return null) }
    }

    override fun lowCoverageVideos() = dao.lowCoverageVideos()
}
