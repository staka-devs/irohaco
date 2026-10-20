package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineSortKey
import dev.irohaco.core.domain.AssetAvailability
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RoomTimelineReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val reader = RoomTimelineReader(db)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    @Test
    fun `pages available media newest first and falls back to the modification time`() = runTest {
        RoomCatalogStore(db).apply(
            CatalogChanges(
                observedAtMs = 1,
                newVolumes = listOf(CatalogVolume("v1", "external_primary", null, null)),
                newAssets = listOf(
                    asset("a", id = 1, taken = 300, modified = 9_000),
                    asset("b", id = 2, taken = null, modified = 200),
                    asset("c", id = 3, taken = 300, modified = 1),
                    asset("d", id = 4, taken = 100, modified = 1),
                    asset("hidden", id = 5, taken = 999, modified = 1),
                ),
                availability = mapOf("s-hidden" to AssetAvailability.INACCESSIBLE),
            ),
        )

        assertEquals(4, reader.count(newest))
        val first = reader.page(newest, offset = 0, limit = 2)
        assertEquals(listOf("c", "a"), first.map { it.assetId }) // same time: asset id breaks the tie
        val rest = reader.page(newest, offset = 2, limit = 10)
        assertEquals(listOf("b" to 200L, "d" to 100L), rest.map { it.assetId to it.sortTimeMs })
        assertEquals(listOf(0, 1, 2, 3), listOf("c", "a", "b", "d").map { reader.indexOf(newest, it) })
        assertEquals(null, reader.indexOf(newest, "hidden"))
        // A date group: from inclusive, until exclusive, hidden files left out, in the timeline's order.
        assertEquals(listOf("c", "a", "b"), reader.between(newest, 200, 301).map { it.assetId })
        assertEquals(listOf("d"), reader.between(newest, 0, 200).map { it.assetId })
    }

    @Test
    fun `sorts by name or size in either direction and filters by kind`() = runTest {
        RoomCatalogStore(db).apply(
            CatalogChanges(
                observedAtMs = 1,
                newVolumes = listOf(CatalogVolume("v1", "external_primary", null, null)),
                newAssets = listOf(
                    asset("a", id = 1, taken = 1, modified = 1, fileName = "beach.jpg", size = 300),
                    asset("b", id = 2, taken = 2, modified = 1, fileName = "Apple.jpg", size = 100),
                    asset("c", id = 3, taken = 3, modified = 1, fileName = "clip.mp4", size = 300, kind = MediaKind.VIDEO),
                ),
            ),
        )
        suspend fun order(query: TimelineQuery) = reader.page(query, 0, 10).map { it.assetId }

        assertEquals(listOf("b", "a", "c"), order(TimelineQuery(TimelineSortKey.NAME, descending = false))) // case-insensitive
        assertEquals(listOf("c", "a", "b"), order(TimelineQuery(TimelineSortKey.NAME, descending = true)))
        assertEquals(listOf("c", "a", "b"), order(TimelineQuery(TimelineSortKey.SIZE, descending = true))) // equal size: id breaks the tie
        assertEquals(listOf("b", "a", "c"), order(TimelineQuery(TimelineSortKey.SIZE, descending = false)))
        assertEquals(listOf("a", "b", "c"), order(TimelineQuery(TimelineSortKey.TAKEN, descending = false)))

        val videos = TimelineQuery(kind = MediaKind.VIDEO)
        assertEquals(1, reader.count(videos))
        assertEquals(listOf("c"), order(videos))
        assertEquals(null, reader.indexOf(videos, "a"))
        val photosByName = TimelineQuery(TimelineSortKey.NAME, descending = false, kind = MediaKind.IMAGE)
        assertEquals(listOf(0, 1), listOf("b", "a").map { reader.indexOf(photosByName, it) })
    }

    private val newest = TimelineQuery()

    private fun asset(
        name: String,
        id: Long,
        taken: Long?,
        modified: Long,
        fileName: String = "$id.jpg",
        size: Long = 1,
        kind: MediaKind = MediaKind.IMAGE,
    ) = NewCatalogAsset(
        discoveryId = "d-$name",
        assetId = name,
        sourceId = "s-$name",
        volumeId = "v1",
        entry = MediaStoreEntry(id, kind, "content://media/$id", fileName, null, "image/jpeg", size, modified, taken),
    )
}
