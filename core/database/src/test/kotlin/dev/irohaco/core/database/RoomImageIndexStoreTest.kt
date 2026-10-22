package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.IndexImageSource
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.ModelArtifact
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One space per signature, one target per image, and only images without a current vector are planned (M2-05). */
@RunWith(RobolectricTestRunner::class)
class RoomImageIndexStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val store = RoomImageIndexStore(db)
    private val sql get() = db.openHelper.writableDatabase
    private val spec = EmbeddingSpaceSpec("m", "d", 2, "L2", "q: ", null, "p")

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private suspend fun setUp() {
        RoomModelProfiles(db).register(ModelArtifact("m", "Model", "https://example.invalid/m", 1, "d"))
        sql.execSQL("INSERT INTO storage_volumes(volume_id, provider_volume_name, state) VALUES ('v', 'external_primary', 'ONLINE')")
        media("a", "IMAGE", "DCIM/Camera/", "AVAILABLE")
        media("b", "IMAGE", "Pictures/", "AVAILABLE")
        media("c", "VIDEO", "DCIM/Camera/", "AVAILABLE")
        media("d", "IMAGE", "DCIM/Camera/", "OFFLINE")
    }

    private fun media(id: String, kind: String, path: String, access: String) {
        sql.execSQL("INSERT INTO media_assets(asset_id, media_kind, first_seen_at) VALUES ('$id', '$kind', 0)")
        sql.execSQL(
            """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, relative_path, mime_type,
                 file_size_bytes, observed_modified_at, access_state)
               VALUES ('s$id', '$id', 'v', ${id[0].code}, 'content://$id', '$id.jpg', '$path', 'image/jpeg', 10, 5, '$access')""",
        )
    }

    @Test
    fun `the same signature gives the same space`() = runTest {
        setUp()
        val first = store.ensureSpace("s1", spec, "sig")
        assertEquals(first, store.ensureSpace("s2", spec, "sig"))
        assertEquals("s1", first)
    }

    @Test
    fun `plans available images in the chosen folders and skips ones already embedded`() = runTest {
        setUp()
        val space = store.ensureSpace("s1", spec, "sig")
        assertEquals(listOf(IndexItem("asset:a", "10:5")), store.planImages(space, setOf("DCIM/Camera/")))
        assertEquals(setOf("asset:a", "asset:b"), store.planImages(space, null).map { it.targetId }.toSet())
        assertEquals(false, store.hasVectors(space))

        val jobs = RoomIndexJobStore(db)
        jobs.create("j", space, IndexJobKind.INITIAL, true, 0, listOf(IndexItem("asset:a", "10:5")))
        jobs.commit("j", IndexItem("asset:a", "10:5"), "v", floatArrayOf(0.6f, 0.8f), 0)
        assertEquals(true, store.hasVectors(space))
        assertEquals(listOf("asset:b"), store.planImages(space, null).map { it.targetId })

        // A new revision of the file is planned again.
        sql.execSQL("UPDATE media_sources SET observed_modified_at = 6 WHERE asset_id = 'a'")
        assertEquals(setOf(IndexItem("asset:a", "10:6"), IndexItem("asset:b", "10:5")), store.planImages(space, null).toSet())
    }

    @Test
    fun `reads where an image is and its revision, and nothing once it is offline`() = runTest {
        setUp()
        store.planImages(store.ensureSpace("s1", spec, "sig"), null)
        assertEquals(IndexImageSource("content://a", "10:5"), store.imageSource("asset:a"))
        sql.execSQL("UPDATE media_sources SET access_state = 'OFFLINE' WHERE asset_id = 'a'")
        assertNull(store.imageSource("asset:a"))
    }
}
