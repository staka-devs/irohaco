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

/** A located file and when it was taken, for naming places and guessing where nearby photos were taken. */
data class TimedPoint(val assetId: String, val timeMs: Long, val point: GeoPoint, val placeName: String?)

/** A place guessed for a file without GPS, from the located file [supportAssetId] taken near it in time (PRD §6). */
data class InferredPlace(val assetId: String, val point: GeoPoint, val placeName: String?, val confidence: Double, val supportAssetId: String)

/** A file whose place name matched: from its own GPS, or [inferred] from a photo taken near it in time. */
data class PlaceMatch(val assetId: String, val placeName: String, val inferred: Boolean)

/**
 * Place names and places guessed for files without GPS, in `location_evidence` (PRD §6, SRCH-003).
 * A guess stays a CANDIDATE: nothing here writes to the file's own EXIF (AC-006).
 */
interface PlaceEvidenceStore {
    /** Up to [limit] areas of about 1 km whose GPS points have no place name yet; each is named once. */
    suspend fun unnamedAreas(limit: Int): List<GeoPoint>

    /** Names every unnamed GPS point in the area of [area]; an empty [name] marks it as looked up and not found. */
    suspend fun nameArea(area: GeoPoint, name: String)

    /** Available files with a GPS point from their file, by capture time. */
    suspend fun locatedTimes(): List<TimedPoint>

    /** Available files whose file was read and had no GPS, with their capture time. */
    suspend fun unlocatedTimes(): List<Pair<String, Long>>

    /** Replaces every guess made from nearby photos with [places], in one transaction. */
    suspend fun replaceInferred(places: List<InferredPlace>)

    /** The place names known, from GPS and, with [includeInferred], from guesses. */
    suspend fun placeNames(includeInferred: Boolean): List<String>

    /** Available files at one of [names], confirmed first, then guesses by confidence. */
    suspend fun filesAt(names: Collection<String>, includeInferred: Boolean, limit: Int): List<PlaceMatch>
}
