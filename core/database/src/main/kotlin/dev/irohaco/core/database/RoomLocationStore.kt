package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.LocatedAsset
import dev.irohaco.core.contracts.LocationStore
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

@Dao
interface LocationDao {
    @Query(
        """SELECT a.asset_id AS assetId, a.media_kind AS mediaKind, MIN(s.content_uri) AS contentUri,
           COALESCE(MAX(s.observed_taken_at), MAX(s.observed_modified_at), a.first_seen_at) AS sortTimeMs, MIN(s.mime_type) AS mimeType
           FROM media_assets a JOIN media_sources s ON s.asset_id = a.asset_id
           WHERE s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL
             AND NOT EXISTS (SELECT 1 FROM location_evidence le WHERE le.asset_id = a.asset_id AND le.evidence_source = 'EXIF')
           GROUP BY a.asset_id LIMIT :limit""",
    )
    suspend fun unindexed(limit: Int): List<TimelineRow>

    @Query("SELECT COUNT(*) FROM location_evidence WHERE asset_id = :assetId AND evidence_source = 'EXIF'")
    suspend fun exifCount(assetId: String): Int

    @Query(
        """INSERT INTO location_evidence(evidence_id, asset_id, evidence_source, evidence_status, latitude, longitude, place_text, confidence, explanation)
           VALUES (:evidenceId, :assetId, 'EXIF', :status, :latitude, :longitude, NULL, :confidence, '')""",
    )
    suspend fun insertExif(evidenceId: String, assetId: String, status: String, latitude: Double?, longitude: Double?, confidence: Double?)

    @Transaction
    suspend fun recordExif(evidenceId: String, assetId: String, point: GeoPoint?) {
        if (exifCount(assetId) > 0) return
        if (point == null) {
            insertExif(evidenceId, assetId, "UNKNOWN", null, null, null)
        } else {
            insertExif(evidenceId, assetId, "CONFIRMED", point.latitude, point.longitude, 1.0)
        }
    }

    @Query(
        """SELECT le.asset_id AS assetId, le.latitude AS latitude, le.longitude AS longitude, MIN(s.content_uri) AS contentUri
           FROM location_evidence le JOIN media_assets a ON a.asset_id = le.asset_id JOIN media_sources s ON s.asset_id = a.asset_id
           WHERE le.evidence_source = 'EXIF' AND le.evidence_status = 'CONFIRMED'
             AND s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL
           GROUP BY le.asset_id
           ORDER BY COALESCE(MAX(s.observed_taken_at), MAX(s.observed_modified_at), a.first_seen_at) DESC""",
    )
    suspend fun located(): List<LocatedRow>

    @Query("SELECT place_text FROM location_evidence WHERE asset_id = :assetId AND evidence_source = 'EXIF' AND place_text IS NOT NULL LIMIT 1")
    suspend fun placeName(assetId: String): String?

    @Query("UPDATE location_evidence SET place_text = :name WHERE asset_id = :assetId AND evidence_source = 'EXIF'")
    suspend fun setPlaceName(assetId: String, name: String)
}

data class LocatedRow(val assetId: String, val latitude: Double, val longitude: Double, val contentUri: String)

/** EXIF and video GPS in `location_evidence`, source EXIF (PRD GAL-017). A file without GPS is kept as UNKNOWN. */
class RoomLocationStore(private val db: IrohacoDatabase, private val newId: () -> String = { UUID.randomUUID().toString() }) : LocationStore {
    private val dao get() = db.locationDao()

    override suspend fun unindexed(limit: Int): List<TimelineItem> =
        dao.unindexed(limit).map { TimelineItem(it.assetId, MediaKind.valueOf(it.mediaKind), it.contentUri, it.sortTimeMs, it.mimeType) }

    override suspend fun recordExif(assetId: String, point: GeoPoint?) = dao.recordExif(newId(), assetId, point)

    /** Newest first, so a map marker can show its group's newest photo. */
    override suspend fun located(): List<LocatedAsset> =
        dao.located().map { LocatedAsset(it.assetId, GeoPoint(it.latitude, it.longitude), it.contentUri) }

    override suspend fun placeName(assetId: String): String? = dao.placeName(assetId)

    override suspend fun setPlaceName(assetId: String, name: String) = dao.setPlaceName(assetId, name)

    override fun changes(): Flow<Unit> =
        db.invalidationTracker.createFlow("location_evidence", "media_sources", "media_assets", emitInitialState = true).map { }
}
