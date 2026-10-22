package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.AnnotationAuthor
import dev.irohaco.core.contracts.AnnotationKind
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexSource
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.VectorHit
import dev.irohaco.core.contracts.VideoQuality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Memos keep every revision, and an edit renews the memo's full-text entry and vector only (M2-11, AC-007). */
@RunWith(RobolectricTestRunner::class)
class RoomAnnotationStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val memos = RoomAnnotationStore(db)
    private val texts = RoomTextIndexStore(db)
    private val media = RoomMediaIndexStore(db)
    private val vectors = RoomVectorSearchStore(db)
    private val sql get() = db.openHelper.writableDatabase

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private suspend fun setUp() {
        RoomModelProfiles(db).register(ModelArtifact("m", "Model", "https://example.invalid/m", 1, "d"))
        sql.execSQL("INSERT INTO storage_volumes(volume_id, provider_volume_name, state) VALUES ('v', 'external_primary', 'ONLINE')")
        sql.execSQL("INSERT INTO media_assets(asset_id, media_kind, first_seen_at) VALUES ('a', 'IMAGE', 0)")
        sql.execSQL(
            """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, mime_type,
                 file_size_bytes, observed_modified_at, access_state)
               VALUES ('sa', 'a', 'v', 1, 'content://a', 'a.jpg', 'image/jpeg', 10, 5, 'AVAILABLE')""",
        )
    }

    @Test
    fun `a memo keeps every revision and full-text search follows its current text`() = runTest {
        setUp()
        memos.create("n", "a", AnnotationKind.CONTEXT, "祖母の家の庭", AnnotationAuthor.USER, null, 1)
        memos.revise("n", "祖父の畑", AnnotationAuthor.USER, null, 2)
        val memo = memos.annotations("a").first().single()
        assertEquals("祖父の畑" to 2, memo.current.text to memo.current.revisionNo)
        assertEquals(listOf(2, 1), memos.history("n").map { it.revisionNo })
        assertEquals(emptyList(), texts.search("祖母", 10))
        assertEquals(listOf(true), texts.search("祖父", 10).map { it.fromMemo })

        memos.setSearchable("n", false)
        assertEquals(emptyList(), texts.search("祖父", 10))
        memos.setSearchable("n", true)
        assertEquals(1, texts.search("畑", 10).size)

        memos.delete("n")
        assertEquals(emptyList(), memos.annotations("a").first())
        assertEquals(emptyList(), texts.search("畑", 10))
    }

    @Test
    fun `a memo on a scene is found at the scene's start, and only an indexed scene takes one`() = runTest {
        setUp()
        sql.execSQL("INSERT INTO media_assets(asset_id, media_kind, first_seen_at) VALUES ('c', 'VIDEO', 0)")
        sql.execSQL(
            """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, mime_type,
                 file_size_bytes, observed_modified_at, observed_duration_ms, access_state)
               VALUES ('sc', 'c', 'v', 3, 'content://c', 'c.mp4', 'video/mp4', 20, 5, 25000, 'AVAILABLE')""",
        )
        assertEquals(false, memos.createOnScene("x", "c", 15_000, AnnotationKind.KEYWORDS, "ろうそく", AnnotationAuthor.USER, null, 1))
        val space = media.ensureSpace("S", EmbeddingSpaceSpec("m", "d", 2, "L2", null, null, "p"), "sig")
        media.plan(space, null, VideoQuality.BALANCED, 0)

        assertEquals(true, memos.createOnScene("n", "c", 15_000, AnnotationKind.KEYWORDS, "ろうそく", AnnotationAuthor.USER, null, 1))
        assertEquals(10_000L until 20_000L, memos.annotations("c").first().single().scene)
        assertEquals(listOf(10_000L), texts.search("ろうそく", 10).map { it.timestampMs })

        val item = media.memoItem("n")!!
        media.putVector(space, item, "v", floatArrayOf(0f, 1f), 0)
        assertEquals(listOf(VectorHit("c", 10_000, fromMemo = true)), vectors.nearest(space, floatArrayOf(0f, 1f), 1))
    }

    @Test
    fun `an AI revision names its model`() = runTest {
        setUp()
        memos.create("n", "a", AnnotationKind.AI_CAPTION, "白い梅", AnnotationAuthor.AI, "m", 1)
        assertEquals("m", memos.history("n").single().modelProfileId)
    }

    @Test
    fun `editing a memo plans the memo again, not the photo, and its old vector stops counting`() = runTest {
        setUp()
        val space = media.ensureSpace("S", EmbeddingSpaceSpec("m", "d", 2, "L2", null, null, "p"), "sig")
        memos.create("n", "a", AnnotationKind.CONTEXT, "祖母の家", AnnotationAuthor.USER, null, 1)
        val planned = media.plan(space, null, VideoQuality.BALANCED, 0)
        assertEquals(setOf(IndexItem("asset:a", "10:5"), IndexItem("memo:n", "rev:1")), planned.toSet())
        assertEquals(IndexSource.Text("祖母の家", "rev:1"), media.source("memo:n"))
        media.putVector(space, IndexItem("asset:a", "10:5"), "v1", floatArrayOf(1f, 0f), 0)
        media.putVector(space, IndexItem("memo:n", "rev:1"), "v2", floatArrayOf(0f, 1f), 0)
        assertEquals(listOf(VectorHit("a", null, fromMemo = true)), vectors.nearest(space, floatArrayOf(0f, 1f), 1))

        memos.revise("n", "祖父の畑", AnnotationAuthor.USER, null, 2)
        assertEquals(listOf(IndexItem("memo:n", "rev:2")), media.plan(space, null, VideoQuality.BALANCED, 0))
        assertEquals(listOf(VectorHit("a", null, fromMemo = false)), vectors.nearest(space, floatArrayOf(0f, 1f), 1))
        assertEquals(IndexItem("memo:n", "rev:2"), media.memoItem("n"))

        memos.setSearchable("n", false)
        assertNull(media.memoItem("n"))
        assertNull(media.source("memo:n"))
    }
}
