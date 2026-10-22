package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.TextMatch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OCR text is found by any part of it, Japanese included, and only in files that are available (M2-07). */
@RunWith(RobolectricTestRunner::class)
class RoomTextIndexStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val store = RoomTextIndexStore(db)
    private val sql get() = db.openHelper.writableDatabase

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private fun setUp() {
        sql.execSQL("INSERT INTO storage_volumes(volume_id, provider_volume_name, state) VALUES ('v', 'external_primary', 'ONLINE')")
        for ((id, kind) in listOf("a" to "IMAGE", "c" to "VIDEO")) {
            sql.execSQL("INSERT INTO media_assets(asset_id, media_kind, first_seen_at) VALUES ('$id', '$kind', 0)")
            sql.execSQL(
                """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, mime_type, file_size_bytes, access_state)
                   VALUES ('s$id', '$id', 'v', ${id[0].code}, 'content://$id', '$id', 'image/jpeg', 10, 'AVAILABLE')""",
            )
        }
        sql.execSQL("INSERT INTO frame_extraction_profiles VALUES ('p', 'FIXED_INTERVAL', 10000, 10, 'p')")
        sql.execSQL("INSERT INTO media_revisions(revision_id, asset_id, file_digest, observed_at) VALUES ('rc', 'c', 'r', 0)")
        sql.execSQL("INSERT INTO video_segments VALUES ('g', 'rc', 'p', 10000, 20000)")
        sql.execSQL("INSERT INTO sampled_frames VALUES ('f', 'g', 15000, 'r')")
    }

    @Test
    fun `finds text by any part of it, in photos and video frames`() = runTest {
        setUp()
        store.recordOcr("asset:a", "r1", "東京駅 丸の内北口\nTokyo Station", "e")
        store.recordOcr("frame:f", "r", "営業時間 10:00〜", "e")
        assertEquals(listOf("a"), store.search("丸の内", 10).map { it.assetId })
        assertEquals(listOf("a"), store.search("駅", 10).map { it.assetId })
        assertEquals(listOf("a"), store.search("ｓｔａｔｉｏｎ", 10).map { it.assetId })
        assertEquals(listOf(TextMatch("c", 15_000, "営業時間 10:00〜")), store.search("営業", 10))
        assertEquals(emptyList(), store.search("丸の外", 10))
        assertEquals(emptyList(), store.search("!?", 10))
    }

    @Test
    fun `a newer revision replaces the old text, and blank text removes it`() = runTest {
        setUp()
        store.recordOcr("asset:a", "r1", "古い看板", "e")
        store.recordOcr("asset:a", "r1", "古い看板", "e")
        store.recordOcr("asset:a", "r2", "新しい看板", "e")
        assertEquals(emptyList(), store.search("古い", 10))
        assertEquals(listOf("新しい看板"), store.search("看板", 10).map { it.text })
        store.recordOcr("asset:a", "r3", "", "e")
        assertEquals(emptyList(), store.search("看板", 10))
        assertEquals(0, sql.query("SELECT count(*) FROM ocr_fts").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun `text of a file that is not available is not shown`() = runTest {
        setUp()
        store.recordOcr("asset:a", "r1", "秘密のメモ", "e")
        sql.execSQL("UPDATE media_sources SET access_state = 'INACCESSIBLE' WHERE asset_id = 'a'")
        assertEquals(emptyList(), store.search("秘密", 10))
    }

    @Test
    fun `terms are characters and their pairs, and queries are phrases of pairs`() {
        assertEquals("東 京 駅 a b 東京 京駅 ab", TextGrams.terms("東京駅、AB"))
        assertEquals("\"東京 京駅\" a", TextGrams.query("東京駅 Ａ"))
        assertNull(TextGrams.query("・・・"))
    }
}
