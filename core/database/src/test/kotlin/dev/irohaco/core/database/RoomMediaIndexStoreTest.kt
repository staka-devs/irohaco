package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.IndexSource
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.VideoQuality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One space per signature, one target per image, and only images without a current vector are planned (M2-05). */
@RunWith(RobolectricTestRunner::class)
class RoomMediaIndexStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val store = RoomMediaIndexStore(db)
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
        assertEquals(listOf(IndexItem("asset:a", "10:5")), store.plan(space, setOf("DCIM/Camera/"), VideoQuality.BALANCED, 0))
        assertEquals(setOf("asset:a", "asset:b"), store.plan(space, null, VideoQuality.BALANCED, 0).map { it.targetId }.toSet())
        assertEquals(false, store.hasVectors(space))

        val jobs = RoomIndexJobStore(db)
        jobs.create("j", space, IndexJobKind.INITIAL, true, 0, listOf(IndexItem("asset:a", "10:5")))
        jobs.commit("j", IndexItem("asset:a", "10:5"), "v", floatArrayOf(0.6f, 0.8f), 0)
        assertEquals(true, store.hasVectors(space))
        assertEquals(listOf("asset:b"), store.plan(space, null, VideoQuality.BALANCED, 0).map { it.targetId })

        // A new revision of the file is planned again.
        sql.execSQL("UPDATE media_sources SET observed_modified_at = 6 WHERE asset_id = 'a'")
        assertEquals(setOf(IndexItem("asset:a", "10:6"), IndexItem("asset:b", "10:5")), store.plan(space, null, VideoQuality.BALANCED, 0).toSet())
    }

    @Test
    fun `reads where an image is and its revision, and nothing once it is offline`() = runTest {
        setUp()
        store.plan(store.ensureSpace("s1", spec, "sig"), null, VideoQuality.BALANCED, 0)
        assertEquals(IndexSource.Image("content://a", "10:5"), store.source("asset:a"))
        sql.execSQL("UPDATE media_sources SET access_state = 'OFFLINE' WHERE asset_id = 'a'")
        assertNull(store.source("asset:a"))
    }

    @Test
    fun `a video gets one segment and frame per interval, read at the frame's time`() = runTest {
        setUp()
        sql.execSQL("UPDATE media_sources SET observed_duration_ms = 25000 WHERE asset_id = 'c'")
        val space = store.ensureSpace("s1", spec, "sig")
        val frames = store.plan(space, null, VideoQuality.BALANCED, 0).filter { it.targetId.startsWith("frame:") }
        assertEquals(3, frames.size)
        assertEquals(setOf("10:5"), frames.map { it.sourceRevisionDigest }.toSet())
        assertEquals(listOf(0L to 10_000L, 10_000L to 20_000L, 20_000L to 25_000L), segments())
        assertEquals(IndexSource.Frame("content://c", 22_500, "10:5"), store.source(frames.last().targetId))

        // Planning again adds nothing; a finer quality adds its own frames next to these.
        assertEquals(3, store.plan(space, null, VideoQuality.BALANCED, 0).count { it.targetId.startsWith("frame:") })
        assertEquals(9, store.plan(space, null, VideoQuality.THOROUGH, 0).count { it.targetId.startsWith("frame:") })
        assertEquals(3 + 9, segments().size)
        assertEquals(0, store.lowCoverageVideos().first())
    }

    @Test
    fun `a video longer than the frame limit is low coverage`() = runTest {
        setUp()
        sql.execSQL("UPDATE media_sources SET observed_duration_ms = ${4 * 3_600_000L} WHERE asset_id = 'c'")
        val frames = store.plan(store.ensureSpace("s1", spec, "sig"), null, VideoQuality.BALANCED, 0).count { it.targetId.startsWith("frame:") }
        assertEquals(VideoQuality.BALANCED.maxFrames, frames)
        assertEquals(1, store.lowCoverageVideos().first())
    }

    private fun segments() = sql.query("SELECT start_ms, end_ms FROM video_segments ORDER BY extraction_profile_id, start_ms").use {
        buildList { while (it.moveToNext()) add(it.getLong(0) to it.getLong(1)) }
    }
}
