package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.HistoryCandidate
import dev.irohaco.core.contracts.InferredPlace
import dev.irohaco.core.contracts.KnownPlace
import dev.irohaco.core.contracts.LocatedAsset
import dev.irohaco.core.contracts.PlaceMatch
import dev.irohaco.core.contracts.TimedPoint
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.domain.GeoBounds
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** GPS read from the files into `location_evidence` (PRD GAL-017). */
@RunWith(RobolectricTestRunner::class)
class RoomLocationStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private var id = 0
    private val store = RoomLocationStore(db) { "e${id++}" }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private suspend fun catalog(vararg names: String) = RoomCatalogStore(db).apply(
        CatalogChanges(
            observedAtMs = 1,
            newVolumes = listOf(CatalogVolume("v1", "external_primary", null, null)),
            newAssets = names.mapIndexed { i, name ->
                NewCatalogAsset("d-$name", name, "s-$name", "v1", MediaStoreEntry(i + 1L, MediaKind.IMAGE, "content://media/$i", "$name.jpg", null, "image/jpeg", 1, 1, takenAtMs = i.toLong()))
            },
        ),
    )

    @Test
    fun `reads each file once, keeps a file without GPS as read, and lists the located ones`() = runTest {
        catalog("tokyo", "nogps", "later")
        val tokyo = GeoPoint(35.68, 139.76)
        assertEquals(setOf("tokyo", "nogps", "later"), store.unindexed(10).map { it.assetId }.toSet())

        store.recordExif("tokyo", tokyo)
        store.recordExif("nogps", null)
        store.recordExif("tokyo", GeoPoint(0.0, 0.0)) // read again by a resumed run: the first record stays

        assertEquals(listOf("later"), store.unindexed(10).map { it.assetId })
        assertEquals(listOf(LocatedAsset("tokyo", tokyo, "content://media/0")), store.located())
    }

    @Test
    fun `lists the located ones newest first, so a map marker shows the newest photo`() = runTest {
        catalog("older", "newer")
        store.recordExif("older", GeoPoint(35.68, 139.76))
        store.recordExif("newer", GeoPoint(35.69, 139.77))
        assertEquals(listOf("newer", "older"), store.located().map { it.assetId })
    }

    @Test
    fun `records a batch at once`() = runTest {
        catalog("a", "b", "c")
        store.recordExif(mapOf("a" to GeoPoint(35.68, 139.76), "b" to null))
        assertEquals(listOf("c"), store.unindexed(10).map { it.assetId })
        assertEquals(listOf("a"), store.located().map { it.assetId })
    }

    @Test
    fun `keeps a place name once looked up`() = runTest {
        catalog("tokyo")
        store.recordExif("tokyo", GeoPoint(35.68, 139.76))
        assertEquals(null, store.placeName("tokyo"))
        store.setPlaceName("tokyo", "千代田区")
        assertEquals("千代田区", store.placeName("tokyo"))
    }

    @Test
    fun `the timeline can be limited to an area on the map`() = runTest {
        catalog("tokyo", "osaka", "nogps")
        store.recordExif("tokyo", GeoPoint(35.68, 139.76))
        store.recordExif("osaka", GeoPoint(34.70, 135.49))
        store.recordExif("nogps", null)
        val kanto = TimelineQuery(area = GeoBounds(south = 35.0, west = 139.0, north = 36.0, east = 140.0))
        assertEquals(listOf("tokyo"), RoomTimelineReader(db).page(kanto, 0, 10).map { it.assetId })
    }

    @Test
    fun `areas are named once, guesses replace the old ones, and a place name finds confirmed files before guesses`() = runTest {
        catalog("gps", "nogps", "unread")
        val tokushima = GeoPoint(34.0703, 134.5548)
        store.recordExif("gps", tokushima)
        store.recordExif("nogps", null)

        assertEquals(listOf(GeoPoint(34.07, 134.55)), store.unnamedAreas(10))
        store.nameArea(GeoPoint(34.07, 134.55), "徳島市, 徳島県")
        assertEquals(emptyList(), store.unnamedAreas(10))
        assertEquals("徳島市, 徳島県", store.placeName("gps"))
        assertEquals(listOf(TimedPoint("gps", 0, tokushima, "徳島市, 徳島県")), store.locatedTimes())
        assertEquals(listOf("nogps" to 1L), store.unlocatedTimes())

        store.replaceInferred(listOf(InferredPlace("nogps", tokushima, "徳島市, 徳島県", 0.8, "gps")))
        store.replaceInferred(listOf(InferredPlace("nogps", tokushima, "徳島市, 徳島県", 0.7, "gps")))
        assertEquals(listOf("徳島市, 徳島県"), store.placeNames(includeInferred = false))
        assertEquals(
            listOf(PlaceMatch("gps", "徳島市, 徳島県", false), PlaceMatch("nogps", "徳島市, 徳島県", true)),
            store.filesAt(listOf("徳島市, 徳島県"), includeInferred = true, limit = 10),
        )
        assertEquals(listOf("gps"), store.filesAt(listOf("徳島市, 徳島県"), includeInferred = false, limit = 10).map { it.assetId })
        // A guess never touches the file's own record.
        assertEquals(listOf(LocatedAsset("gps", tokushima, "content://media/0")), store.located())
    }

    @Test
    fun `an area looked up without a result is not a place name`() = runTest {
        catalog("sea")
        store.recordExif("sea", GeoPoint(30.0, 140.0))
        store.nameArea(GeoPoint(30.0, 140.0), "")
        assertEquals(emptyList(), store.unnamedAreas(10))
        assertEquals(null, store.placeName("sea"))
        assertEquals(emptyList(), store.placeNames(includeInferred = true))
    }

    @Test
    fun `history candidates replace nearby guesses, a new import keeps decisions, and the search finds them as history`() = runTest {
        catalog("gps", "a", "b", "c")
        val kyoto = GeoPoint(35.0116, 135.7681)
        store.recordExif("gps", kyoto)
        store.setPlaceName("gps", "京都市")
        listOf("a", "b", "c").forEach { store.recordExif(it, null) }
        store.replaceInferred(listOf(InferredPlace("a", kyoto, "京都市", 0.6, "gps"), InferredPlace("c", kyoto, "京都市", 0.6, "gps")))

        store.replaceHistory(listOf(HistoryCandidate("a", kyoto, "京都市", 0.9), HistoryCandidate("b", kyoto, null, 0.7)))
        assertEquals(listOf("b", "a"), store.undecided().first().map { it.item.assetId })
        assertEquals(0.7, store.undecided("b").first()?.confidence)
        assertEquals(null, store.undecided("c").first())
        // "c" has no GPS and no candidate; "a" and "b" wait for theirs.
        assertEquals(listOf("c"), store.unmatched().first().map { it.assetId })
        assertEquals(listOf(KnownPlace("京都市", kyoto, 1)), store.knownPlaces().first())
        // The history's place wins for "a"; "c" keeps its guess from nearby photos.
        assertEquals(
            listOf(PlaceMatch("gps", "京都市", false), PlaceMatch("a", "京都市", true, history = true), PlaceMatch("c", "京都市", true)),
            store.filesAt(listOf("京都市"), includeInferred = true, limit = 10),
        )
        // A guess made later does not replace the history's candidate either.
        store.replaceInferred(listOf(InferredPlace("a", kyoto, "京都市", 0.6, "gps")))
        assertEquals(1, store.filesAt(listOf("京都市"), includeInferred = true, limit = 10).count { it.assetId == "a" })

        store.decide(listOf("a"), confirmed = false)
        store.replaceHistory(listOf(HistoryCandidate("a", kyoto, "京都市", 0.9)))
        assertEquals(emptyList(), store.undecided().first().map { it.item.assetId })
    }
}
