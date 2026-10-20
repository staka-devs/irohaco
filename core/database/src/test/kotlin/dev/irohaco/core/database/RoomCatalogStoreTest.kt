package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.VolumeSync
import dev.irohaco.core.domain.AssetAvailability
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class RoomCatalogStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val store = RoomCatalogStore(db)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    @Test
    fun `stores new volumes and assets through a resolved discovery`() = runTest {
        store.apply(firstSync())

        assertEquals(listOf(CatalogVolume("v1", "external_primary", "ver1", 5)), store.volumes())
        val sources = store.knownSources("v1").sortedBy { it.mediaStoreId }
        assertEquals(listOf(1L to MediaKind.IMAGE, 2L to MediaKind.VIDEO), sources.map { it.mediaStoreId to it.kind })
        assertEquals(1, count("SELECT count(*) FROM identity_resolutions WHERE resolution_kind = 'NEW_ASSET' AND selected_asset_id = 'a1'"))
        assertEquals(2, count("SELECT count(*) FROM media_discoveries WHERE discovery_state = 'RESOLVED'"))
        assertEquals(1, count("SELECT count(*) FROM media_sources WHERE observed_taken_at = 42"))
        assertEquals(0, count("SELECT count(*) FROM media_revisions"))
        assertEquals(0, count("SELECT count(*) FROM pragma_foreign_key_check"))
    }

    @Test
    fun `refreshes sources, changes availability and takes volumes offline`() = runTest {
        store.apply(firstSync())
        store.apply(
            CatalogChanges(
                observedAtMs = 2,
                offlineVolumeIds = setOf("v1"),
                refreshedSources = mapOf("s1" to entry(1, MediaKind.IMAGE).copy(displayName = "renamed.jpg")),
                availability = mapOf("s2" to AssetAvailability.OFFLINE),
                syncedVolumes = listOf(VolumeSync("v1", "ver1", 9)),
            ),
        )
        assertEquals(1, count("SELECT count(*) FROM media_sources WHERE source_id = 's1' AND display_name = 'renamed.jpg'"))
        assertEquals(AssetAvailability.OFFLINE, store.knownSources("v1").single { it.sourceId == "s2" }.availability)
        assertEquals(1, count("SELECT count(*) FROM storage_volumes WHERE state = 'OFFLINE'"))
        assertEquals(9L, store.volumes().single().lastGeneration)
    }

    @Test
    fun `a detached source frees its MediaStore id for a new asset in the same transaction`() = runTest {
        store.apply(firstSync())
        store.apply(
            CatalogChanges(
                observedAtMs = 3,
                detachedSourceIds = setOf("s1"),
                availability = mapOf("s1" to AssetAvailability.MISSING_CANDIDATE),
                newAssets = listOf(NewCatalogAsset("d3", "a3", "s3", "v1", entry(1, MediaKind.IMAGE))),
                syncedVolumes = listOf(VolumeSync("v1", "ver2", 1)),
            ),
        )
        val old = store.knownSources("v1").single { it.sourceId == "s1" }
        assertNull(old.mediaStoreId)
        assertEquals(AssetAvailability.MISSING_CANDIDATE, old.availability)
        assertEquals(1L, store.knownSources("v1").single { it.sourceId == "s3" }.mediaStoreId)
    }

    private fun firstSync() = CatalogChanges(
        observedAtMs = 1,
        newVolumes = listOf(CatalogVolume("v1", "external_primary", null, null)),
        onlineVolumeIds = setOf("v1"),
        newAssets = listOf(
            NewCatalogAsset("d1", "a1", "s1", "v1", entry(1, MediaKind.IMAGE)),
            NewCatalogAsset("d2", "a2", "s2", "v1", entry(2, MediaKind.VIDEO)),
        ),
        syncedVolumes = listOf(VolumeSync("v1", "ver1", 5)),
    )

    private fun entry(id: Long, kind: MediaKind) = MediaStoreEntry(
        mediaStoreId = id,
        kind = kind,
        contentUri = "content://media/external_primary/file/$id",
        displayName = "$id",
        relativePath = "DCIM/",
        mimeType = if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4",
        sizeBytes = 10,
        modifiedAtMs = 1,
        takenAtMs = if (id == 1L) 42 else null,
    )

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }
}
