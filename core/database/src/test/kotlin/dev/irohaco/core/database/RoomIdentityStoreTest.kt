package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.AssetMarks
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.TimelineQuery
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
class RoomIdentityStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val catalog = RoomCatalogStore(db)
    private val identity = RoomIdentityStore(db)
    private val marks = RoomMarksStore(db)
    private val collections = RoomCollectionsStore(db)
    private val timeline = RoomTimelineReader(db)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private fun entry(id: Long, name: String, path: String, taken: Long?) =
        MediaStoreEntry(id, MediaKind.IMAGE, "content://media/$id", name, path, "image/jpeg", 1, 1, taken)

    private fun asset(name: String, entry: MediaStoreEntry) = NewCatalogAsset("d-$name", name, "s-$name", "v", entry)

    /** "old" was tagged and rated, then went missing; "new" turns up elsewhere with the same name and time. */
    private suspend fun seed(newName: String = "IMG_1.jpg", newTaken: Long? = 100) {
        catalog.apply(
            CatalogChanges(
                observedAtMs = 1,
                newVolumes = listOf(CatalogVolume("v", "external_primary", null, null)),
                newAssets = listOf(asset("old", entry(1, "IMG_1.jpg", "DCIM/Camera/", 100)), asset("other", entry(2, "IMG_2.jpg", "DCIM/Camera/", 100))),
            ),
        )
        marks.setFavorite(listOf("old"), true)
        marks.setRating("old", 4)
        collections.addTag(listOf("old"), "Trip")
        val album = collections.createAlbum("Holiday")
        collections.addToAlbum(album, listOf("old"))
        catalog.apply(
            CatalogChanges(
                observedAtMs = 2,
                availability = mapOf("s-old" to AssetAvailability.MISSING_CANDIDATE, "s-other" to AssetAvailability.MISSING_CANDIDATE),
                newAssets = listOf(asset("new", entry(3, newName, "Pictures/Moved/", newTaken))),
            ),
        )
    }

    @Test
    fun `a matching name and time is offered, never merged on its own`() = runTest {
        seed()
        assertEquals(1, identity.findCandidates())
        assertEquals(0, identity.findCandidates()) // already recorded
        val candidate = identity.pending().first().single()
        assertEquals(listOf("old", "new", "DCIM/Camera/", "Pictures/Moved/"), listOf(candidate.missingAssetId, candidate.newAssetId, candidate.missingFolder, candidate.newFolder))
        assertEquals(listOf("new"), timeline.page(TimelineQuery(), 0, 10).map { it.assetId }) // still its own asset
    }

    @Test
    fun `a name alone or a missing time is not a candidate`() = runTest {
        seed(newTaken = null)
        assertEquals(0, identity.findCandidates())
    }

    @Test
    fun `linking moves the file and keeps the marks, tags and album of the old asset`() = runTest {
        seed()
        marks.setFavorite(listOf("new"), false)
        collections.addTag(listOf("new"), "Moved")
        identity.findCandidates()
        identity.link("d-new", "old", atMs = 3)

        assertEquals(listOf("old"), timeline.page(TimelineQuery(), 0, 10).map { it.assetId })
        assertEquals(AssetMarks(favorite = true, rating = 4), marks.marks("old").first())
        assertEquals(listOf("Moved", "Trip"), collections.tagsOf("old").first().map { it.name })
        assertEquals(1, collections.albums().first().single().itemCount)
        assertEquals(emptyList(), identity.pending().first())
    }

    @Test
    fun `a rejected candidate is not offered again`() = runTest {
        seed()
        identity.findCandidates()
        identity.reject("d-new", "old")
        assertEquals(0, identity.findCandidates())
        assertEquals(emptyList(), identity.pending().first())
        assertEquals(listOf("new"), timeline.page(TimelineQuery(), 0, 10).map { it.assetId })
    }
}
