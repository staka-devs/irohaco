package dev.irohaco.core.application

import dev.irohaco.core.contracts.HistoryCandidate
import dev.irohaco.core.contracts.HistoryEntry
import dev.irohaco.core.contracts.HistoryReview
import dev.irohaco.core.contracts.LocationHistoryStore
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.UnreadableHistoryException
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationHistoryTest {
    private val min = 60_000L
    private val kyoto = GeoPoint(35.0, 135.7)
    private val road = GeoPoint(35.1, 135.8)

    @Test
    fun `a photo in a stay takes its place, else the nearest point in time, less sure the further`() {
        val entries = listOf(
            HistoryEntry.Visit(100 * min, 200 * min, kyoto, "Kyoto Station"),
            HistoryEntry.Point(300 * min, road),
        )
        val photos = listOf("stay" to 150 * min, "close" to 303 * min, "far" to 290 * min, "none" to 400 * min, "before" to 50 * min)
        assertEquals(
            listOf(
                HistoryCandidate("stay", kyoto, "Kyoto Station", LocationHistory.IN_VISIT),
                HistoryCandidate("close", road, null, LocationHistory.NEAR_POINT),
                HistoryCandidate("far", road, null, LocationHistory.FAR_POINT),
            ),
            LocationHistory.match(photos, entries),
        )
    }

    @Test
    fun `only entries near a photo in time are kept`() {
        val times = longArrayOf(100 * min, 500 * min)
        assertEquals(true, LocationHistory.near(times, 110 * min, 120 * min))
        assertEquals(true, LocationHistory.near(times, 50 * min, 90 * min))
        assertEquals(false, LocationHistory.near(times, 200 * min, 300 * min))
        assertEquals(false, LocationHistory.near(longArrayOf(), 0, 1))
    }

    private val photo = TimelineItem("p", MediaKind.IMAGE, "content://p", 0)
    private val undecided = MutableStateFlow<List<HistoryReview>>(emptyList())
    private var replaced: List<HistoryCandidate>? = null
    private val decided = mutableListOf<Pair<List<String>, Boolean>>()
    private val set = mutableListOf<Pair<String, GeoPoint>>()
    private val store = object : LocationHistoryStore {
        override suspend fun unlocatedTimes() = listOf("p" to 150 * min)
        override suspend fun replaceHistory(candidates: List<HistoryCandidate>) {
            replaced = candidates
        }
        override fun undecided() = undecided
        override suspend fun decide(assetIds: List<String>, confirmed: Boolean) {
            decided += assetIds to confirmed
        }
    }

    @Test
    fun `an import keeps the matched places, and an unreadable file changes nothing`() = runTest {
        val history = LocationHistory(store, { _, keep -> listOf(HistoryEntry.Visit(100 * min, 200 * min, kyoto, null)).filter { keep(100 * min, 200 * min) } }, { _, _ -> })
        assertEquals(LocationHistory.Imported.Matched(1), history.import("content://timeline"))
        assertEquals(listOf(HistoryCandidate("p", kyoto, null, LocationHistory.IN_VISIT)), replaced)

        replaced = null
        val broken = LocationHistory(store, { _, _ -> throw UnreadableHistoryException(UnreadableHistoryException.Reason.UNKNOWN_FORMAT) }, { _, _ -> })
        assertEquals(LocationHistory.Imported.Unreadable(UnreadableHistoryException.Reason.UNKNOWN_FORMAT), broken.import("content://other"))
        assertEquals(null, replaced)
    }

    @Test
    fun `candidates group by day and place, and confirming sets the place as an edit`() = runTest {
        val day = 86_400_000L
        undecided.value = listOf(
            HistoryReview(photo.copy(assetId = "a", sortTimeMs = day + 1), kyoto, "Kyoto", 0.9),
            HistoryReview(photo.copy(assetId = "b", sortTimeMs = day + 2), kyoto, "Kyoto", 0.7),
            HistoryReview(photo.copy(assetId = "c", sortTimeMs = day + 3), road, null, 0.5),
        )
        val history = LocationHistory(store, { _, _ -> emptyList() }, { id, point -> set += id to point }) { ZoneOffset.UTC }
        val groups = history.groups().first()
        assertEquals(listOf(listOf("a", "b"), listOf("c")), groups.map { group -> group.reviews.map { it.item.assetId } })
        assertEquals(LocalDate.of(1970, 1, 2), groups.first().date)
        assertEquals(0.7, groups.first().confidence)

        history.decide(groups.first().reviews, confirmed = true)
        history.decide(groups.last().reviews, confirmed = false)
        assertEquals(listOf("a" to kyoto, "b" to kyoto), set)
        assertEquals(listOf(listOf("a", "b") to true, listOf("c") to false), decided)
    }
}
