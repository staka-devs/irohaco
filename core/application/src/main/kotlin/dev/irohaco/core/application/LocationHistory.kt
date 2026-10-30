package dev.irohaco.core.application

import dev.irohaco.core.contracts.HistoryCandidate
import dev.irohaco.core.contracts.HistoryEntry
import dev.irohaco.core.contracts.HistoryReview
import dev.irohaco.core.contracts.LocationHistoryReader
import dev.irohaco.core.contracts.LocationHistoryStore
import dev.irohaco.core.contracts.UnreadableHistoryException
import dev.irohaco.core.domain.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Candidates of one day at one place, confirmed or rejected together (ADR-0029). */
data class HistoryGroup(val date: LocalDate, val placeName: String?, val point: GeoPoint, val confidence: Double, val reviews: List<HistoryReview>)

/**
 * Places for files without GPS from the location history the user exported (#174, ADR-0029). Only the
 * places matched to a photo are kept, never the history itself, and a candidate changes no file: confirming
 * it sets the place in the DB like an edit (MET-002), which the viewer can then write to the file (#45).
 */
class LocationHistory(
    private val store: LocationHistoryStore,
    private val reader: LocationHistoryReader,
    /** Sets a file's place in the DB as the user's edit (EditMetadata). */
    private val setLocation: suspend (assetId: String, point: GeoPoint) -> Unit,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    sealed interface Imported {
        data class Matched(val count: Int) : Imported

        data class Unreadable(val reason: UnreadableHistoryException.Reason) : Imported
    }

    suspend fun import(contentUri: String): Imported {
        val photos = store.unlocatedTimes().sortedBy { it.second }
        val times = photos.map { it.second }.toLongArray()
        val entries = try {
            reader.read(contentUri) { start, end -> near(times, start, end) }
        } catch (e: UnreadableHistoryException) {
            return Imported.Unreadable(e.reason)
        }
        val candidates = match(photos, entries)
        store.replaceHistory(candidates)
        return Imported.Matched(candidates.size)
    }

    /** The undecided candidates by day and place, newest first. */
    fun groups(): Flow<List<HistoryGroup>> = store.undecided().map { reviews ->
        val zone = zone()
        reviews.groupBy { Instant.ofEpochMilli(it.item.sortTimeMs).atZone(zone).toLocalDate() to (it.placeName ?: area(it.point)) }
            .map { (key, group) -> HistoryGroup(key.first, group.first().placeName, group.first().point, group.minOf { it.confidence }, group) }
    }

    /** Confirming sets each file's place in the DB, as the user's edit; the files stay as they are. */
    suspend fun decide(reviews: List<HistoryReview>, confirmed: Boolean) {
        if (confirmed) reviews.forEach { setLocation(it.item.assetId, it.point) }
        store.decide(reviews.map { it.item.assetId }, confirmed)
    }

    companion object {
        /** How far from a recorded point in time a photo still takes its place. */
        const val NEAR_MS = 15 * 60_000L
        const val IN_VISIT = 0.9
        const val NEAR_POINT = 0.7
        const val FAR_POINT = 0.5

        private fun area(point: GeoPoint) = "%.2f,%.2f".format(point.latitude, point.longitude)

        /** Whether a photo was taken from [NEAR_MS] before [start] until [NEAR_MS] after [end]; [times] is sorted. */
        internal fun near(times: LongArray, start: Long, end: Long): Boolean {
            val at = times.binarySearch(start - NEAR_MS).let { if (it < 0) -it - 1 else it }
            return at < times.size && times[at] <= end + NEAR_MS
        }

        /**
         * A photo taken during a stay is at that place; otherwise it takes the recorded point nearest in time
         * within [NEAR_MS], less sure the further it is. A photo with neither gets nothing.
         */
        internal fun match(photos: List<Pair<String, Long>>, entries: List<HistoryEntry>): List<HistoryCandidate> {
            val visits = entries.filterIsInstance<HistoryEntry.Visit>().sortedBy { it.startMs }
            val points = entries.filterIsInstance<HistoryEntry.Point>().sortedBy { it.timeMs }
            val visitStarts = visits.map { it.startMs }.toLongArray()
            val pointTimes = points.map { it.timeMs }.toLongArray()
            return photos.mapNotNull { (assetId, time) ->
                // The last stay that began by then, found by search: a history holds thousands of them.
                val started = visitStarts.binarySearch(time).let { if (it < 0) -it - 2 else it }
                visits.getOrNull(started)?.takeIf { time <= it.endMs }?.let {
                    return@mapNotNull HistoryCandidate(assetId, it.point, it.placeName, IN_VISIT)
                }
                val at = pointTimes.binarySearch(time).let { if (it < 0) -it - 1 else it }
                val nearest = listOfNotNull(points.getOrNull(at - 1), points.getOrNull(at)).minByOrNull { abs(it.timeMs - time) }
                    ?.takeIf { abs(it.timeMs - time) <= NEAR_MS } ?: return@mapNotNull null
                HistoryCandidate(assetId, nearest.point, null, if (abs(nearest.timeMs - time) <= NEAR_MS / 3) NEAR_POINT else FAR_POINT)
            }
        }
    }
}
