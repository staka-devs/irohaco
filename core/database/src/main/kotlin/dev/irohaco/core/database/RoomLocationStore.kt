package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.InferredPlace
import dev.irohaco.core.contracts.LocatedAsset
import dev.irohaco.core.contracts.LocationStore
import dev.irohaco.core.contracts.PlaceEvidenceStore
import dev.irohaco.core.contracts.PlaceMatch
import dev.irohaco.core.contracts.TimedPoint
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

    @Transaction
    suspend fun recordExifAll(points: Map<String, GeoPoint?>, newId: () -> String) {
        points.forEach { (assetId, point) -> recordExif(newId(), assetId, point) }
    }

    // An empty name marks an area looked up without a result (NamePlaces); the viewer asks again for its own file.
    @Query("SELECT place_text FROM location_evidence WHERE asset_id = :assetId AND evidence_source = 'EXIF' AND place_text IS NOT NULL AND place_text <> '' LIMIT 1")
    suspend fun placeName(assetId: String): String?

    @Query("UPDATE location_evidence SET place_text = :name WHERE asset_id = :assetId AND evidence_source = 'EXIF'")
    suspend fun setPlaceName(assetId: String, name: String)

    @Query(
        """SELECT ROUND(latitude, 2) AS latitude, ROUND(longitude, 2) AS longitude FROM location_evidence
           WHERE evidence_source = 'EXIF' AND evidence_status = 'CONFIRMED' AND place_text IS NULL
           GROUP BY ROUND(latitude, 2), ROUND(longitude, 2) LIMIT :limit""",
    )
    suspend fun unnamedAreas(limit: Int): List<AreaRow>

    @Query(
        """UPDATE location_evidence SET place_text = :name
           WHERE evidence_source = 'EXIF' AND evidence_status = 'CONFIRMED' AND place_text IS NULL
             AND ROUND(latitude, 2) = :latitude AND ROUND(longitude, 2) = :longitude""",
    )
    suspend fun nameArea(latitude: Double, longitude: Double, name: String)

    @Query(
        """SELECT le.asset_id AS assetId, $TAKEN AS timeMs, le.latitude AS latitude, le.longitude AS longitude, le.place_text AS placeName
           FROM location_evidence le JOIN media_assets a ON a.asset_id = le.asset_id JOIN media_sources s ON s.asset_id = a.asset_id
           WHERE le.evidence_source = 'EXIF' AND le.evidence_status = 'CONFIRMED' AND s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL
           GROUP BY le.asset_id""",
    )
    suspend fun locatedTimes(): List<TimedRow>

    @Query(
        """SELECT le.asset_id AS assetId, $TAKEN AS timeMs
           FROM location_evidence le JOIN media_assets a ON a.asset_id = le.asset_id JOIN media_sources s ON s.asset_id = a.asset_id
           WHERE le.evidence_source = 'EXIF' AND le.evidence_status = 'UNKNOWN' AND s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL
           GROUP BY le.asset_id""",
    )
    suspend fun unlocatedTimes(): List<AssetTime>

    @Query(
        """DELETE FROM location_evidence_support WHERE evidence_id IN
             (SELECT evidence_id FROM location_evidence WHERE evidence_source = 'NEARBY_MEDIA' AND evidence_status = 'CANDIDATE')""",
    )
    suspend fun deleteInferredSupport()

    @Query("DELETE FROM location_evidence WHERE evidence_source = 'NEARBY_MEDIA' AND evidence_status = 'CANDIDATE'")
    suspend fun deleteInferred()

    @Query(
        """INSERT INTO location_evidence(evidence_id, asset_id, evidence_source, evidence_status, latitude, longitude, place_text, confidence, explanation)
           VALUES ('near:' || :assetId, :assetId, 'NEARBY_MEDIA', 'CANDIDATE', :latitude, :longitude, :placeName, :confidence, 'Taken close in time to a located photo')""",
    )
    suspend fun insertInferred(assetId: String, latitude: Double, longitude: Double, placeName: String?, confidence: Double)

    @Query("INSERT INTO location_evidence_support(evidence_id, supporting_asset_id) VALUES ('near:' || :assetId, :supportAssetId)")
    suspend fun insertSupport(assetId: String, supportAssetId: String)

    @Transaction
    suspend fun replaceInferred(places: List<InferredPlace>) {
        deleteInferredSupport()
        deleteInferred()
        places.forEach {
            insertInferred(it.assetId, it.point.latitude, it.point.longitude, it.placeName, it.confidence)
            insertSupport(it.assetId, it.supportAssetId)
        }
    }

    @Query(
        """SELECT DISTINCT place_text FROM location_evidence WHERE place_text IS NOT NULL AND place_text <> ''
             AND ((evidence_source = 'EXIF' AND evidence_status = 'CONFIRMED')
                  OR (:includeInferred AND evidence_source = 'NEARBY_MEDIA' AND evidence_status = 'CANDIDATE'))""",
    )
    suspend fun placeNames(includeInferred: Boolean): List<String>

    @Query(
        """SELECT le.asset_id AS assetId, le.place_text AS placeName, le.evidence_source <> 'EXIF' AS inferred
           FROM location_evidence le JOIN media_assets a ON a.asset_id = le.asset_id
           WHERE le.place_text IN (:names) AND a.tombstoned_at IS NULL
             AND EXISTS (SELECT 1 FROM media_sources s WHERE s.asset_id = a.asset_id AND +s.access_state = 'AVAILABLE')
             AND ((le.evidence_source = 'EXIF' AND le.evidence_status = 'CONFIRMED')
                  OR (:includeInferred AND le.evidence_source = 'NEARBY_MEDIA' AND le.evidence_status = 'CANDIDATE'))
           ORDER BY inferred, le.confidence DESC LIMIT :limit""",
    )
    suspend fun filesAt(names: List<String>, includeInferred: Boolean, limit: Int): List<PlaceMatch>
}

data class LocatedRow(val assetId: String, val latitude: Double, val longitude: Double, val contentUri: String)

data class AreaRow(val latitude: Double, val longitude: Double)

data class TimedRow(val assetId: String, val timeMs: Long, val latitude: Double, val longitude: Double, val placeName: String?)

data class AssetTime(val assetId: String, val timeMs: Long)

/** Capture time, else modified time, else when the catalog first saw the file: the timeline's order. */
private const val TAKEN = "COALESCE(MAX(s.observed_taken_at), MAX(s.observed_modified_at), a.first_seen_at)"

/** EXIF and video GPS in `location_evidence`, source EXIF (PRD GAL-017). A file without GPS is kept as UNKNOWN. */
class RoomLocationStore(private val db: IrohacoDatabase, private val newId: () -> String = { UUID.randomUUID().toString() }) : LocationStore, PlaceEvidenceStore {
    private val dao get() = db.locationDao()

    override suspend fun unindexed(limit: Int): List<TimelineItem> =
        dao.unindexed(limit).map { TimelineItem(it.assetId, MediaKind.valueOf(it.mediaKind), it.contentUri, it.sortTimeMs, it.mimeType) }

    override suspend fun recordExif(assetId: String, point: GeoPoint?) = dao.recordExif(newId(), assetId, point)

    override suspend fun recordExif(points: Map<String, GeoPoint?>) = dao.recordExifAll(points, newId)

    /** Newest first, so a map marker can show its group's newest photo. */
    override suspend fun located(): List<LocatedAsset> =
        dao.located().map { LocatedAsset(it.assetId, GeoPoint(it.latitude, it.longitude), it.contentUri) }

    override suspend fun placeName(assetId: String): String? = dao.placeName(assetId)

    override suspend fun setPlaceName(assetId: String, name: String) = dao.setPlaceName(assetId, name)

    override fun changes(): Flow<Unit> =
        db.invalidationTracker.createFlow("location_evidence", "media_sources", "media_assets", emitInitialState = true).map { }

    override suspend fun unnamedAreas(limit: Int) = dao.unnamedAreas(limit).map { GeoPoint(it.latitude, it.longitude) }

    override suspend fun nameArea(area: GeoPoint, name: String) = dao.nameArea(area.latitude, area.longitude, name)

    override suspend fun locatedTimes() = dao.locatedTimes().map { TimedPoint(it.assetId, it.timeMs, GeoPoint(it.latitude, it.longitude), it.placeName) }

    override suspend fun unlocatedTimes() = dao.unlocatedTimes().map { it.assetId to it.timeMs }

    override suspend fun replaceInferred(places: List<InferredPlace>) = dao.replaceInferred(places)

    override suspend fun placeNames(includeInferred: Boolean) = dao.placeNames(includeInferred)

    override suspend fun filesAt(names: Collection<String>, includeInferred: Boolean, limit: Int) =
        dao.filesAt(names.toList(), includeInferred, limit)
}
