package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.NewCatalogAsset
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

        assertEquals(4, reader.count())
        val first = reader.page(offset = 0, limit = 2)
        assertEquals(listOf("c", "a"), first.map { it.assetId }) // same time: asset id breaks the tie
        val rest = reader.page(offset = 2, limit = 10)
        assertEquals(listOf("b" to 200L, "d" to 100L), rest.map { it.assetId to it.sortTimeMs })
    }

    private fun asset(name: String, id: Long, taken: Long?, modified: Long) = NewCatalogAsset(
        discoveryId = "d-$name",
        assetId = name,
        sourceId = "s-$name",
        volumeId = "v1",
        entry = MediaStoreEntry(id, MediaKind.IMAGE, "content://media/$id", "$id.jpg", null, "image/jpeg", 1, modified, taken),
    )
}
