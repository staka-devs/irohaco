package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.BackupContents
import dev.irohaco.core.contracts.BackupManifest
import dev.irohaco.core.contracts.BackupRow
import dev.irohaco.core.contracts.RestoreMatches
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** A backup finds its files here by name, size and time, adds only to what is here, and keeps nothing when it fails (UC-RESTORE-01, AC-011). */
@RunWith(RobolectricTestRunner::class)
class RoomRestoreStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val store = RoomRestoreStore(db)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private fun sql(vararg statements: String) = statements.forEach(db.openHelper.writableDatabase::execSQL)

    private fun <T> query(q: String, read: (android.database.Cursor) -> T): List<T> = db.openHelper.readableDatabase.query(q).use { c ->
        buildList { while (c.moveToNext()) add(read(c)) }
    }

    private var sources = 0

    /** A file on this phone, with its own id. */
    private fun file(assetId: String, name: String, size: Long = 2048) = sql(
        "INSERT INTO media_assets VALUES ('$assetId', 'IMAGE', 1, NULL)",
        """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, relative_path, mime_type,
             file_size_bytes, observed_modified_at, observed_taken_at, observed_duration_ms, access_state)
           VALUES ('s${sources++}', '$assetId', 'v', NULL, 'content://media/$assetId', '$name', 'DCIM/', 'image/jpeg', $size, 5, 1700000000000, NULL, 'AVAILABLE')""",
    )

    private fun exported(id: String, name: String) = BackupRow(
        id,
        mapOf("asset_id" to id, "media_kind" to "IMAGE", "display_name" to name, "file_size_bytes" to 2048L, "observed_taken_at" to 1700000000000L),
    )

    private fun contents(tables: Map<String, List<BackupRow>>) = BackupContents("b1", 1, 1, BackupManifest("m", emptyMap()), tables)

    private val backup = contents(
        mapOf(
            "assets" to listOf(exported("old-pier", "pier.jpg"), exported("old-twin", "twin.jpg"), exported("old-gone", "gone.jpg")),
            "tags" to listOf(BackupRow("t-sea", mapOf("tag_id" to "t-sea", "name" to "海", "parent_tag_id" to null))),
            "media_tags" to listOf(
                BackupRow("old-pier|t-sea", mapOf("asset_id" to "old-pier", "tag_id" to "t-sea")),
                BackupRow("old-gone|t-sea", mapOf("asset_id" to "old-gone", "tag_id" to "t-sea")),
            ),
            "media_ratings" to listOf(BackupRow("old-pier", mapOf("asset_id" to "old-pier", "rating" to 5L, "is_favorite" to 1L))),
            "annotation_subjects" to listOf(BackupRow("asset:old-pier", mapOf("subject_id" to "asset:old-pier", "asset_id" to "old-pier", "segment_id" to null))),
            "semantic_annotations" to listOf(
                BackupRow("n1", mapOf("annotation_id" to "n1", "subject_id" to "asset:old-pier", "annotation_kind" to "CONTEXT", "searchable" to 1L, "exportable" to 1L, "created_at" to 0L)),
            ),
            "semantic_annotation_revisions" to listOf(
                BackupRow("n1|1", mapOf("annotation_id" to "n1", "revision_no" to 1L, "author" to "USER", "text" to "祖母の家", "modified_at" to 0L)),
            ),
        ),
    )

    private fun setUpPhone() {
        sql("INSERT INTO storage_volumes VALUES ('v', 'external_primary', 'ONLINE')")
        file("new-pier", "pier.jpg")
        file("twin-a", "twin.jpg")
        file("twin-b", "twin.jpg")
        sql(
            "INSERT INTO tags VALUES ('t-local', '海', NULL)",
            "INSERT INTO media_ratings VALUES ('new-pier', 2, 0)", // set on this phone; the backup's 5 must not replace it
        )
    }

    @Test
    fun `one file found by name, size and time is matched, two are left to check, none is missing`() = runTest {
        setUpPhone()
        assertEquals(RestoreMatches(matched = 1, needsReview = 1, missing = 1), store.stage("r1", "content://doc", backup, 9))
        val bindings = query("SELECT exported_asset_id, local_asset_id, match_decision FROM restore_asset_bindings ORDER BY exported_asset_id") {
            Triple(it.getString(0), it.getString(1), it.getString(2))
        }
        assertEquals(
            listOf(Triple("old-gone", null, "SKIPPED"), Triple("old-pier", "new-pier", "MATCHED"), Triple("old-twin", null, "NEEDS_REVIEW")),
            bindings,
        )
        assertEquals(listOf("PREVIEW"), query("SELECT restore_state FROM restore_sessions") { it.getString(0) })
        assertEquals(emptyList(), query("SELECT tag_id FROM media_tags") { it.getString(0) }) // nothing applied yet
    }

    @Test
    fun `records of matched files are added onto what is here, never over it`() = runTest {
        setUpPhone()
        store.stage("r1", "content://doc", backup, 9)
        val result = store.apply("r1", backup, 10)
        assertEquals(listOf("new-pier|t-local"), query("SELECT asset_id || '|' || tag_id FROM media_tags") { it.getString(0) })
        assertEquals(listOf(2), query("SELECT rating FROM media_ratings") { it.getInt(0) })
        assertEquals(listOf("asset:new-pier|祖母の家"), query("SELECT n.subject_id || '|' || r.text FROM semantic_annotations n JOIN semantic_annotation_revisions r USING (annotation_id)") { it.getString(0) })
        assertEquals(mapOf("media_tags" to 1, "annotation_subjects" to 1, "semantic_annotations" to 1, "semantic_annotation_revisions" to 1), result.added)
        assertTrue(result.skipped >= 2) // the missing file's tag and the rating already here
        assertEquals(listOf("COMMITTED"), query("SELECT restore_state FROM restore_sessions") { it.getString(0) })
    }

    @Test
    fun `a restore that breaks halfway keeps nothing`() = runTest {
        setUpPhone()
        // A value for an override that removes the tag: the schema refuses it, after the tag link was already added.
        val broken = contents(
            backup.tables + mapOf(
                "metadata_override_sets" to listOf(
                    BackupRow("o1", mapOf("override_id" to "o1", "asset_id" to "old-pier", "namespace" to "EXIF", "tag_name" to "Artist", "edit_mode" to "REMOVE", "local_revision_no" to 1L, "modified_at" to 0L)),
                ),
                "metadata_override_values" to listOf(BackupRow("o1|0", mapOf("override_id" to "o1", "occurrence" to 0L, "text_value" to "x"))),
            ),
        )
        store.stage("r1", "content://doc", broken, 9)
        assertFails { store.apply("r1", broken, 10) }
        assertEquals(emptyList(), query("SELECT tag_id FROM media_tags") { it.getString(0) })
        assertEquals(emptyList(), query("SELECT override_id FROM metadata_override_sets") { it.getString(0) })
        assertEquals(listOf("ROLLED_BACK"), query("SELECT restore_state FROM restore_sessions") { it.getString(0) })
    }
}
