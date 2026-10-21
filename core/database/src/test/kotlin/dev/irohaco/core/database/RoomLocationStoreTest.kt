package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.LocatedAsset
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.domain.GeoBounds
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaKind
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
                NewCatalogAsset("d-$name", name, "s-$name", "v1", MediaStoreEntry(i + 1L, MediaKind.IMAGE, "content://media/$i", "$name.jpg", null, "image/jpeg", 1, 1, 1))
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
        assertEquals(listOf(LocatedAsset("tokyo", tokyo)), store.located())
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
}
