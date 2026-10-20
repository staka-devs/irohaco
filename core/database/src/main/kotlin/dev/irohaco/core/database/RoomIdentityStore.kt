package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.IdentityCandidate
import dev.irohaco.core.contracts.IdentityStore
import kotlinx.coroutines.flow.Flow

/** The new source of a discovery that was imported as its own asset, matched against missing sources. */
private const val MATCHES = """
    FROM media_discoveries d
    JOIN identity_resolutions r ON r.discovery_id = d.discovery_id AND r.resolution_kind = 'NEW_ASSET'
    JOIN media_sources sn ON sn.asset_id = r.selected_asset_id AND sn.access_state = 'AVAILABLE'
    JOIN media_sources so ON so.access_state = 'MISSING_CANDIDATE' AND so.asset_id <> sn.asset_id
         AND so.display_name = sn.display_name AND so.observed_taken_at = sn.observed_taken_at
    JOIN media_assets o ON o.asset_id = so.asset_id AND o.tombstoned_at IS NULL
    WHERE NOT EXISTS (SELECT 1 FROM media_sources sa WHERE sa.asset_id = so.asset_id AND sa.access_state = 'AVAILABLE')"""

@Dao
interface IdentityDao {
    // Name and capture time only make a candidate; SHA256 or a name alone never does (INT-003).
    @Query("INSERT OR IGNORE INTO identity_candidates(discovery_id, asset_id, confidence, candidate_state) SELECT DISTINCT d.discovery_id, so.asset_id, 0.6, 'PENDING' $MATCHES")
    suspend fun insertCandidates(): Long

    @Query(
        """INSERT OR IGNORE INTO identity_candidate_signals(discovery_id, asset_id, signal_code, signal_digest)
           SELECT c.discovery_id, c.asset_id, x.code, NULL FROM identity_candidates c,
             (SELECT 'FILE_NAME' AS code UNION ALL SELECT 'EXIF_TIME') x
           WHERE c.candidate_state = 'PENDING'""",
    )
    suspend fun insertSignals()

    @Query("SELECT count(*) FROM identity_candidates")
    suspend fun candidateCount(): Int

    @Query(
        """SELECT c.discovery_id AS discoveryId, c.asset_id AS missingAssetId, r.selected_asset_id AS newAssetId,
                  sn.display_name AS fileName, so.relative_path AS missingFolder, sn.relative_path AS newFolder,
                  sn.content_uri AS newContentUri
           FROM identity_candidates c
           JOIN identity_resolutions r ON r.discovery_id = c.discovery_id AND r.resolution_kind = 'NEW_ASSET'
           JOIN media_sources sn ON sn.asset_id = r.selected_asset_id AND sn.access_state = 'AVAILABLE'
           JOIN media_sources so ON so.asset_id = c.asset_id AND so.access_state = 'MISSING_CANDIDATE'
           WHERE c.candidate_state = 'PENDING'
             AND NOT EXISTS (SELECT 1 FROM media_sources sa WHERE sa.asset_id = c.asset_id AND sa.access_state = 'AVAILABLE')
           GROUP BY c.discovery_id, c.asset_id
           ORDER BY sn.display_name""",
    )
    fun pending(): Flow<List<IdentityCandidate>>

    @Query("SELECT selected_asset_id FROM identity_resolutions WHERE discovery_id = :discoveryId AND resolution_kind = 'NEW_ASSET'")
    suspend fun newAssetOf(discoveryId: String): String?

    @Query("UPDATE media_sources SET asset_id = :to WHERE asset_id = :from")
    suspend fun moveSources(from: String, to: String)

    // Marks merge: a favorite on either side stays, and the older rating wins over the newer one.
    @Query(
        """INSERT OR IGNORE INTO media_ratings(asset_id, rating, is_favorite)
           SELECT :to, NULL, 0 FROM media_ratings WHERE asset_id = :from""",
    )
    suspend fun ensureRating(from: String, to: String)

    @Query(
        """UPDATE media_ratings SET
             is_favorite = max(is_favorite, (SELECT is_favorite FROM media_ratings WHERE asset_id = :from)),
             rating = COALESCE(rating, (SELECT rating FROM media_ratings WHERE asset_id = :from))
           WHERE asset_id = :to AND EXISTS (SELECT 1 FROM media_ratings WHERE asset_id = :from)""",
    )
    suspend fun mergeRating(from: String, to: String)

    @Query("DELETE FROM media_ratings WHERE asset_id = :from")
    suspend fun deleteRating(from: String)

    @Query("INSERT OR IGNORE INTO media_tags(asset_id, tag_id) SELECT :to, tag_id FROM media_tags WHERE asset_id = :from")
    suspend fun copyTags(from: String, to: String)

    @Query("DELETE FROM media_tags WHERE asset_id = :from")
    suspend fun deleteTags(from: String)

    @Query(
        """UPDATE album_items SET asset_id = :to WHERE asset_id = :from
             AND album_id NOT IN (SELECT album_id FROM album_items WHERE asset_id = :to)""",
    )
    suspend fun moveAlbumItems(from: String, to: String)

    @Query("DELETE FROM album_items WHERE asset_id = :from")
    suspend fun deleteAlbumItems(from: String)

    @Query("UPDATE media_assets SET tombstoned_at = :atMs WHERE asset_id = :assetId")
    suspend fun tombstone(assetId: String, atMs: Long)

    @Query(
        """UPDATE identity_resolutions SET resolution_kind = 'LINK_EXISTING', selected_asset_id = :assetId,
             decided_by = 'USER', resolved_at = :atMs WHERE discovery_id = :discoveryId""",
    )
    suspend fun resolveLink(discoveryId: String, assetId: String, atMs: Long)

    @Query(
        """UPDATE identity_candidates SET candidate_state = CASE WHEN asset_id = :assetId THEN 'ACCEPTED' ELSE 'REJECTED' END
           WHERE discovery_id = :discoveryId""",
    )
    suspend fun accept(discoveryId: String, assetId: String)

    @Query(
        """INSERT OR IGNORE INTO identity_candidate_signals(discovery_id, asset_id, signal_code, signal_digest)
           VALUES (:discoveryId, :assetId, 'USER_CONFIRMATION', NULL)""",
    )
    suspend fun confirm(discoveryId: String, assetId: String)

    @Query("UPDATE identity_candidates SET candidate_state = 'REJECTED' WHERE discovery_id = :discoveryId AND asset_id = :assetId")
    suspend fun reject(discoveryId: String, assetId: String)

    @Transaction
    suspend fun link(discoveryId: String, missing: String, atMs: Long) {
        val fresh = checkNotNull(newAssetOf(discoveryId)) { "discovery $discoveryId is not a new asset" }
        moveSources(fresh, missing)
        ensureRating(fresh, missing)
        mergeRating(fresh, missing)
        deleteRating(fresh)
        copyTags(fresh, missing)
        deleteTags(fresh)
        moveAlbumItems(fresh, missing)
        deleteAlbumItems(fresh)
        tombstone(fresh, atMs)
        resolveLink(discoveryId, missing, atMs)
        accept(discoveryId, missing)
        confirm(discoveryId, missing)
    }
}

class RoomIdentityStore(private val db: IrohacoDatabase) : IdentityStore {
    private val dao get() = db.identityDao()

    override suspend fun findCandidates(): Int {
        val before = dao.candidateCount()
        dao.insertCandidates()
        dao.insertSignals()
        return dao.candidateCount() - before
    }

    override fun pending(): Flow<List<IdentityCandidate>> = dao.pending()

    override suspend fun link(discoveryId: String, missingAssetId: String, atMs: Long) = dao.link(discoveryId, missingAssetId, atMs)

    override suspend fun reject(discoveryId: String, missingAssetId: String) = dao.reject(discoveryId, missingAssetId)
}
