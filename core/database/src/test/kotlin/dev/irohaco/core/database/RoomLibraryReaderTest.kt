package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.domain.AssetAvailability
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RoomLibraryReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val timeline = RoomTimelineReader(db)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private suspend fun seed() {
        val store = RoomCatalogStore(db)
        store.apply(
            CatalogChanges(
                observedAtMs = 1,
                newVolumes = listOf(CatalogVolume("internal", "external_primary", null, null), CatalogVolume("sd", "1234-5678", null, null)),
                newAssets = listOf(
                    asset("a", "internal", 1, "DCIM/Camera/"),
                    asset("b", "internal", 2, "DCIM/Camera/"),
                    asset("c", "internal", 3, "Pictures/Trip/"),
                    asset("d", "internal", 4, null),
                    asset("gone", "internal", 5, "Pictures/Trip/"),
                    asset("e", "sd", 1, "DCIM/"),
                ),
                availability = mapOf("s-gone" to AssetAvailability.MISSING_CANDIDATE),
            ),
        )
    }

    @Test
    fun `counts available media per folder and keeps an unplugged card's folders`() = runTest {
        seed()
        val reader = RoomLibraryReader(db)
        assertEquals(
            listOf(
                LibraryFolder(FolderRef("sd", "DCIM/"), "1234-5678", offline = false, itemCount = 1),
                LibraryFolder(FolderRef("internal", ""), "external_primary", offline = false, itemCount = 1),
                LibraryFolder(FolderRef("internal", "DCIM/Camera/"), "external_primary", offline = false, itemCount = 2),
                LibraryFolder(FolderRef("internal", "Pictures/Trip/"), "external_primary", offline = false, itemCount = 1),
            ),
            reader.folders(),
        )

        RoomCatalogStore(db).apply(
            CatalogChanges(observedAtMs = 2, offlineVolumeIds = setOf("sd"), availability = mapOf("s-e" to AssetAvailability.OFFLINE)),
        )
        assertEquals(LibraryFolder(FolderRef("sd", "DCIM/"), "1234-5678", offline = true, itemCount = 1), reader.folders().first())
        assertEquals(4, timeline.count(TimelineQuery())) // offline media leaves the timeline but not the library
    }

    @Test
    fun `the timeline shows one folder or hides excluded ones`() = runTest {
        seed()
        suspend fun ids(query: TimelineQuery) = timeline.page(query, 0, 10).map { it.assetId }.sorted()

        assertEquals(listOf("a", "b"), ids(TimelineQuery(folder = FolderRef("internal", "DCIM/Camera/"))))
        assertEquals(listOf("d"), ids(TimelineQuery(folder = FolderRef("internal", ""))))
        assertEquals(listOf("d", "e"), ids(TimelineQuery(excludedFolders = setOf(FolderRef("internal", "DCIM/Camera/"), FolderRef("internal", "Pictures/Trip/")))))
        assertEquals(null, timeline.indexOf(TimelineQuery(excludedFolders = setOf(FolderRef("internal", "DCIM/Camera/"))), "a"))
    }

    private fun asset(name: String, volume: String, id: Long, path: String?) = NewCatalogAsset(
        discoveryId = "d-$name",
        assetId = name,
        sourceId = "s-$name",
        volumeId = volume,
        entry = MediaStoreEntry(id, MediaKind.IMAGE, "content://media/$volume/$id", "$name.jpg", path, "image/jpeg", 1, 1, 1),
    )
}
