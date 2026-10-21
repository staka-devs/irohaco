package dev.irohaco.core.contracts

import dev.irohaco.core.domain.GeoPoint
import kotlinx.coroutines.flow.Flow

/** An available photo or video whose file says where it was taken (PRD GAL-017). */
data class LocatedAsset(val assetId: String, val point: GeoPoint, val contentUri: String = "")

/** The EXIF and video GPS read into `location_evidence` as source EXIF (GAL-017). */
interface LocationStore {
    /** Available items whose GPS has not been read yet, at most [limit]. */
    suspend fun unindexed(limit: Int): List<TimelineItem>

    /** What the file says: a point, or null when it has none, so it is not read again. */
    suspend fun recordExif(assetId: String, point: GeoPoint?)

    /** Several files in one write, so the map sees one change per batch instead of one per file (M1-33). */
    suspend fun recordExif(points: Map<String, GeoPoint?>) {
        points.forEach { (assetId, point) -> recordExif(assetId, point) }
    }

    /** Every available item with a GPS point from its file. */
    suspend fun located(): List<LocatedAsset>

    suspend fun placeName(assetId: String): String?

    suspend fun setPlaceName(assetId: String, name: String)

    fun changes(): Flow<Unit>
}

/** Turns a point into a place name; this goes outside the device (ADR-0011). */
fun interface PlaceNames {
    suspend fun nameOf(point: GeoPoint): String?
}

/** Where the map's tiles come from (ADR-0011). */
enum class MapProvider { OPEN_FREE_MAP, GOOGLE }

interface MapSettings {
    val provider: Flow<MapProvider>

    suspend fun setProvider(provider: MapProvider)

    /** Providers the user allowed to receive the map's area and the place names asked for. */
    val consented: Flow<Set<MapProvider>>

    suspend fun setConsent(provider: MapProvider, granted: Boolean)
}
