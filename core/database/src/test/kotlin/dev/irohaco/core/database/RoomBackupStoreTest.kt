package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.BackupManifest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The export reads what the user made and how to find each file again, and nothing a sync or an index makes (UC-BACKUP-01). */
@RunWith(RobolectricTestRunner::class)
class RoomBackupStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val store = RoomBackupStore(db)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private fun sql(vararg statements: String) = statements.forEach(db.openHelper.writableDatabase::execSQL)

    private fun file(assetId: String, name: String) = sql(
        "INSERT INTO media_assets VALUES ('$assetId', 'IMAGE', 1, NULL)",
        """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, relative_path, mime_type,
             file_size_bytes, observed_modified_at, observed_taken_at, observed_duration_ms, access_state)
           VALUES ('s-$assetId', '$assetId', 'v', NULL, 'content://media/$assetId', '$name', 'DCIM/Camera/', 'image/jpeg', 2048, 5, 1700000000000, NULL, 'AVAILABLE')""",
    )

    @Test
    fun `user data comes with the files it points at, found again by name, size and time`() = runTest {
        sql("INSERT INTO storage_volumes VALUES ('v', 'external_primary', 'ONLINE')")
        file("tagged", "pier.jpg")
        file("untouched", "other.jpg")
        sql(
            "INSERT INTO tags VALUES ('t1', '海', NULL)",
            "INSERT INTO media_tags VALUES ('tagged', 't1')",
            "INSERT INTO annotation_subjects VALUES ('sub', 'tagged', NULL)",
            "INSERT INTO semantic_annotations VALUES ('n1', 'sub', 'CONTEXT', 1, 1, 0)",
            "INSERT INTO semantic_annotation_revisions VALUES ('n1', 1, 'USER', NULL, '祖母の家', 0)",
        )
        val tables = store.snapshot()
        val asset = tables.getValue("assets").single()
        assertEquals("tagged", asset.key)
        assertEquals("pier.jpg" to 2048L, asset.values["display_name"] to asset.values["file_size_bytes"])
        assertEquals(1700000000000L, asset.values["observed_taken_at"])
        assertTrue("content_uri" !in asset.values)
        assertEquals(listOf("tagged|t1"), tables.getValue("media_tags").map { it.key })
        assertEquals("祖母の家", tables.getValue("semantic_annotation_revisions").single().values["text"])
        assertEquals(listOf("sub"), tables.getValue("annotation_subjects").map { it.key })
        assertTrue("embedding_vectors" !in tables && "egress_grants" !in tables && "provider_connections" !in tables)
    }

    @Test
    fun `a backup is staging until it is verified with its manifest, or failed`() = runTest {
        store.begin("b1", "content://doc/1", 7)
        store.finish("b1", BackupManifest("m", mapOf("tags" to mapOf("t1" to "h1", "t2" to "h2"))))
        store.begin("b2", "content://doc/2", 8)
        store.finish("b2", null)
        val rows = db.openHelper.readableDatabase.query("SELECT backup_id, state, manifest_sha256 FROM backup_archives ORDER BY backup_id").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
        }
        assertEquals(listOf(Triple("b1", "VERIFIED", "m"), Triple("b2", "FAILED", "")), rows)
        val entries = db.openHelper.readableDatabase.query("SELECT count(*) FROM backup_archive_entries WHERE backup_id = 'b1'").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(2, entries)
    }
}
