package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.Album
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineSortKey
import dev.irohaco.core.domain.AssetAvailability
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RoomCollectionsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private var ids = 0
    private val store = RoomCollectionsStore(db) { "id${ids++}" }
    private val timeline = RoomTimelineReader(db)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private fun entry(id: Long) = MediaStoreEntry(id, MediaKind.IMAGE, "content://media/$id", "$id.jpg", "DCIM/", "image/jpeg", 1, id, id)

    private suspend fun seed() = RoomCatalogStore(db).apply(
        CatalogChanges(
            observedAtMs = 1,
            newVolumes = listOf(CatalogVolume("v", "external_primary", null, null)),
            newAssets = listOf("a", "b", "c").mapIndexed { i, name -> NewCatalogAsset("d-$name", name, "s-$name", "v", entry(i + 1L)) },
        ),
    )

    @Test
    fun `tags are reused by name and shown on the timeline`() = runTest {
        seed()
        store.addTag(listOf("a", "b"), " Trip ")
        store.addTag(listOf("b", "c"), "Trip")
        store.addTag(listOf("a"), "beach")
        assertEquals(listOf("beach" to 1, "Trip" to 3), store.tags().first().map { it.name to it.itemCount })
        val trip = store.tags().first().single { it.name == "Trip" }.tagId
        assertEquals(listOf("beach", "Trip"), store.tagsOf("a").first().map { it.name })

        store.removeTag("b", trip)
        assertEquals(listOf("a", "c"), timeline.page(TimelineQuery(TimelineSortKey.NAME, descending = false, tagId = trip), 0, 10).map { it.assetId })
    }

    @Test
    fun `an album keeps the order items were added and survives an unplugged card`() = runTest {
        seed()
        val album = store.createAlbum("Holiday")
        store.addToAlbum(album, listOf("c", "a"))
        store.addToAlbum(album, listOf("a", "b")) // "a" keeps its place
        val query = TimelineQuery(albumId = album)
        assertEquals(listOf("c", "a", "b"), timeline.page(query, 0, 10).map { it.assetId })
        assertEquals(2, timeline.indexOf(query, "b"))
        assertEquals(listOf(Album(album, "Holiday", 3, "content://media/3")), store.albums().first())

        RoomCatalogStore(db).apply(CatalogChanges(observedAtMs = 2, availability = mapOf("s-c" to AssetAvailability.OFFLINE)))
        assertEquals(listOf(Album(album, "Holiday", 2, "content://media/1")), store.albums().first())
        RoomCatalogStore(db).apply(CatalogChanges(observedAtMs = 3, availability = mapOf("s-c" to AssetAvailability.AVAILABLE)))
        assertEquals(listOf("c", "a", "b"), timeline.page(query, 0, 10).map { it.assetId })
    }
}
