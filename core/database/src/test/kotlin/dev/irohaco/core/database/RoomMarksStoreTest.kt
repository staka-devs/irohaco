package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.AssetMarks
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
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class RoomMarksStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val marks = RoomMarksStore(db)
    private val timeline = RoomTimelineReader(db)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private fun entry(id: Long, path: String) = MediaStoreEntry(id, MediaKind.IMAGE, "content://media/$id", "$id.jpg", path, "image/jpeg", 1, id, id)

    private suspend fun seed() = RoomCatalogStore(db).apply(
        CatalogChanges(
            observedAtMs = 1,
            newVolumes = listOf(CatalogVolume("v", "external_primary", null, null)),
            newAssets = listOf("a", "b", "c").mapIndexed { i, name -> NewCatalogAsset("d-$name", name, "s-$name", "v", entry(i + 1L, "DCIM/")) },
        ),
    )

    @Test
    fun `favorites and ratings stay with the asset after a move and while offline`() = runTest {
        seed()
        assertEquals(AssetMarks(), marks.marks("a").first())
        marks.setFavorite(listOf("a", "b"), true)
        marks.setRating("a", 4)
        marks.setFavorite(listOf("b"), false)
        assertEquals(AssetMarks(favorite = true, rating = 4), marks.marks("a").first())
        assertEquals(AssetMarks(favorite = false, rating = null), marks.marks("b").first())

        // The file moves (same MediaStore row), then its card goes offline.
        RoomCatalogStore(db).apply(CatalogChanges(observedAtMs = 2, refreshedSources = mapOf("s-a" to entry(1, "Pictures/Trip/"))))
        RoomCatalogStore(db).apply(CatalogChanges(observedAtMs = 3, availability = mapOf("s-a" to AssetAvailability.OFFLINE)))
        assertEquals(AssetMarks(favorite = true, rating = 4), marks.marks("a").first())

        marks.setRating("a", null)
        assertEquals(AssetMarks(favorite = true, rating = null), marks.marks("a").first())
        assertFailsWith<IllegalArgumentException> { marks.setRating("a", 6) }
    }

    @Test
    fun `the timeline sorts by rating and shows favorites only`() = runTest {
        seed()
        marks.setRating("b", 5)
        marks.setRating("c", 2)
        marks.setFavorite(listOf("c"), true)
        suspend fun ids(query: TimelineQuery) = timeline.page(query, 0, 10).map { it.assetId }

        assertEquals(listOf("b", "c", "a"), ids(TimelineQuery(TimelineSortKey.RATING, descending = true)))
        assertEquals(listOf("a", "c", "b"), ids(TimelineQuery(TimelineSortKey.RATING, descending = false)))
        assertEquals(listOf("c"), ids(TimelineQuery(favoritesOnly = true)))
        assertEquals(1, timeline.count(TimelineQuery(favoritesOnly = true)))
    }
}
