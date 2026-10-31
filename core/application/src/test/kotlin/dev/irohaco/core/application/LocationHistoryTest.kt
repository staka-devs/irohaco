package dev.irohaco.core.application

import dev.irohaco.core.contracts.HistoryCandidate
import dev.irohaco.core.contracts.HistoryEntry
import dev.irohaco.core.contracts.HistoryReview
import dev.irohaco.core.contracts.KnownPlace
import dev.irohaco.core.contracts.LocationHistoryStore
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.UnreadableHistoryException
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val unmatched = MutableStateFlow<List<TimelineItem>>(emptyList())
    private val known = MutableStateFlow<List<KnownPlace>>(emptyList())
    private var replaced: List<HistoryCandidate>? = null
    private val decided = mutableListOf<Pair<List<String>, Boolean>>()
    private val set = mutableListOf<Pair<String, GeoPoint>>()
    private val store = object : LocationHistoryStore {
        override suspend fun unlocatedTimes() = listOf("p" to 150 * min)
        override suspend fun replaceHistory(candidates: List<HistoryCandidate>) {
            replaced = candidates
        }
        override fun undecided() = undecided
        override fun undecided(assetId: String) = undecided.map { all -> all.find { it.item.assetId == assetId } }
        override fun unmatched() = unmatched
        override fun knownPlaces() = known
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
    fun `by place, the days of one place come together, and the dates keep only the photos taken then`() = runTest {
        val day = 86_400_000L
        undecided.value = listOf(
            HistoryReview(photo.copy(assetId = "late", sortTimeMs = 3 * day), kyoto, "Kyoto", 0.9),
            HistoryReview(photo.copy(assetId = "road", sortTimeMs = 2 * day), road, null, 0.5),
            HistoryReview(photo.copy(assetId = "early", sortTimeMs = day), kyoto, "Kyoto", 0.7),
        )
        val history = LocationHistory(store, { _, _ -> emptyList() }, { _, _ -> }) { ZoneOffset.UTC }

        val byPlace = history.groups().first()
        assertEquals(listOf(listOf("late", "early"), listOf("road")), byPlace.map { group -> group.reviews.map { it.item.assetId } })
        assertEquals(LocalDate.of(1970, 1, 2) to LocalDate.of(1970, 1, 4), byPlace.first().from to byPlace.first().to)

        val dated = history.groups(fromMs = 2 * day, untilMs = 3 * day).first()
        assertEquals(listOf(listOf("road")), dated.map { group -> group.reviews.map { it.item.assetId } })
    }

    @Test
    fun `places to pick are those of other files, then the candidates' others, and picking one sets it as an edit`() = runTest {
        known.value = listOf(KnownPlace("Kyoto", kyoto, 5))
        undecided.value = listOf(
            HistoryReview(photo.copy(assetId = "a"), kyoto, "Kyoto", 0.9),
            HistoryReview(photo.copy(assetId = "b"), road, "Road", 0.5),
            HistoryReview(photo.copy(assetId = "c"), road, null, 0.5),
        )
        unmatched.value = listOf(photo.copy(assetId = "new", sortTimeMs = 200 * min), photo.copy(assetId = "old", sortTimeMs = 100 * min))
        val history = LocationHistory(store, { _, _ -> emptyList() }, { id, point -> set += id to point })

        assertEquals(listOf(KnownPlace("Kyoto", kyoto, 5), KnownPlace("Road", road, 1)), history.places().first())
        assertEquals(listOf("old"), history.unmatched(untilMs = 150 * min).first().map { it.assetId })
        history.place(listOf("new", "old"), road)
        assertEquals(listOf("new" to road, "old" to road), set)
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
        val groups = history.groups(byDay = true).first()
        assertEquals(listOf(listOf("a", "b"), listOf("c")), groups.map { group -> group.reviews.map { it.item.assetId } })
        assertEquals(LocalDate.of(1970, 1, 2), groups.first().from)
        assertEquals(0.7, groups.first().confidence)
        assertEquals(listOf(listOf("c")), history.groups(setOf("c")).first().map { group -> group.reviews.map { it.item.assetId } })
        assertEquals(kyoto, history.candidate("b").first()?.point)
        assertEquals(null, history.candidate("z").first())

        history.decide(groups.first().reviews, confirmed = true)
        history.decide(groups.last().reviews, confirmed = false)
        assertEquals(listOf("a" to kyoto, "b" to kyoto), set)
        assertEquals(listOf(listOf("a", "b") to true, listOf("c") to false), decided)
    }
}
