package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.ImageIndexStore
import dev.irohaco.core.contracts.IndexImageSource
import dev.irohaco.core.contracts.IndexItem

// shortcut: a revision is the file's size and modified time until catalog sync records media_revisions digests;
// a pixel edit that keeps both is missed until then.
private const val REVISION =
    """(SELECT MIN(s.file_size_bytes || ':' || COALESCE(s.observed_modified_at, 0)) FROM media_sources s
        WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE')"""

@Dao
interface ImageIndexDao {
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
           WHERE a.media_kind = 'IMAGE' AND a.tombstoned_at IS NULL
             AND EXISTS (SELECT 1 FROM media_sources s WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE'
                         AND (:everywhere OR COALESCE(s.relative_path, '') IN (:folders)))""",
    )
    suspend fun insertTargets(everywhere: Boolean, folders: List<String>)

    @Query(
        """SELECT targetId, sourceRevisionDigest FROM (
             SELECT t.target_id AS targetId, $REVISION AS sourceRevisionDigest
             FROM embedding_targets t JOIN media_assets a ON a.asset_id = t.asset_id
             WHERE a.media_kind = 'IMAGE' AND a.tombstoned_at IS NULL
               AND EXISTS (SELECT 1 FROM media_sources s WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE'
                           AND (:everywhere OR COALESCE(s.relative_path, '') IN (:folders))))
           WHERE NOT EXISTS (SELECT 1 FROM embedding_vectors v WHERE v.space_id = :spaceId
                             AND v.target_id = targetId AND v.source_revision_digest = sourceRevisionDigest)""",
    )
    suspend fun unindexed(spaceId: String, everywhere: Boolean, folders: List<String>): List<IndexItem>

    @Transaction
    suspend fun planImages(spaceId: String, everywhere: Boolean, folders: List<String>): List<IndexItem> {
        insertTargets(everywhere, folders)
        return unindexed(spaceId, everywhere, folders)
    }

    @Query(
        """SELECT (SELECT MIN(s.content_uri) FROM media_sources s WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE') AS contentUri,
             $REVISION AS revisionDigest
           FROM embedding_targets t JOIN media_assets a ON a.asset_id = t.asset_id
           WHERE t.target_id = :targetId AND a.tombstoned_at IS NULL""",
    )
    suspend fun imageSource(targetId: String): IndexSourceRow?
}

data class IndexSourceRow(val contentUri: String?, val revisionDigest: String?)

/** Embedding spaces and one target per image asset (M2-05). */
class RoomImageIndexStore(private val db: IrohacoDatabase) : ImageIndexStore {
    private val dao get() = db.imageIndexDao()

    override suspend fun ensureSpace(newSpaceId: String, spec: EmbeddingSpaceSpec, signature: String) = dao.ensureSpace(newSpaceId, spec, signature)

    override suspend fun hasVectors(spaceId: String) = dao.hasVectors(spaceId)

    override suspend fun planImages(spaceId: String, folders: Set<String>?) = dao.planImages(spaceId, folders == null, folders.orEmpty().toList())

    override suspend fun imageSource(targetId: String): IndexImageSource? {
        val row = dao.imageSource(targetId) ?: return null
        return IndexImageSource(row.contentUri ?: return null, row.revisionDigest ?: return null)
    }
}
