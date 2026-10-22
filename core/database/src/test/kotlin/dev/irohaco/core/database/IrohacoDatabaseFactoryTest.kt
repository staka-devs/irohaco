package dev.irohaco.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class IrohacoDatabaseFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val schemaSql = File("../../database/schema.sql").readText()
    private var db: IrohacoDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    @Test
    fun `splits every table, index and trigger of schema sql`() {
        val statements = splitStatements(schemaSql)
        assertEquals(54, statements.count { it.startsWith("CREATE TABLE") })
        assertEquals(24, statements.count { it.matches(Regex("^CREATE (UNIQUE )?INDEX[\\s\\S]*")) })
        assertEquals(32, statements.count { it.startsWith("CREATE TRIGGER") })
        assertTrue(statements.all { it.endsWith(";") })
    }

    @Test
    fun `creates the reference schema and Room accepts it`() {
        val sql = open().openHelper.writableDatabase
        assertEquals(54, sql.count("table", excludeInternal = true))
        assertEquals(24, sql.count("index", excludeInternal = true))
        assertEquals(32, sql.count("trigger", excludeInternal = true))
        assertEquals("ok", sql.single("PRAGMA integrity_check"))
        sql.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test
    fun `enforces foreign keys, checks and triggers`() {
        val sql = open().openHelper.writableDatabase
        assertFailsWith<SQLiteConstraintException> {
            sql.execSQL("INSERT INTO media_tags(asset_id, tag_id) VALUES ('missing', 'missing')")
        }
        assertFailsWith<SQLiteConstraintException> {
            sql.execSQL("INSERT INTO media_assets VALUES ('a1', 'AUDIO', 1, NULL)")
        }
        sql.execSQL("INSERT INTO media_assets VALUES ('a1', 'IMAGE', 1, NULL)")
        sql.execSQL("INSERT INTO media_revisions VALUES ('r1', 'a1', 'f', 'v', 1, 10, 10, NULL)")
        sql.execSQL("INSERT INTO frame_extraction_profiles VALUES ('p1', 'FIXED_INTERVAL', 10000, 100, 'd1')")
        assertFailsWith<SQLiteConstraintException> { // trigger ensure_video_segment: segments need a VIDEO asset
            sql.execSQL("INSERT INTO video_segments VALUES ('s1', 'r1', 'p1', 0, 1000)")
        }
    }

    @Test
    fun `reopens an existing database without rebuilding it`() {
        open().openHelper.writableDatabase.execSQL("INSERT INTO media_assets VALUES ('a1', 'IMAGE', 1, NULL)")
        db!!.close()
        db = IrohacoDatabaseFactory.open(context)
        assertEquals("1", db!!.openHelper.writableDatabase.single("SELECT count(*) FROM media_assets"))
    }

    @Test
    fun `refuses a database that does not match the entities`() {
        val drifted = schemaSql.replace("CREATE INDEX ix_media_tags_tag ON media_tags(tag_id);", "")
        assertFailsWith<IllegalStateException> { IrohacoDatabaseFactory.open(context, drifted, IrohacoDatabaseFactory.NAME) }
        assertTrue(!context.getDatabasePath(IrohacoDatabaseFactory.NAME).exists())
    }

    private fun open(): IrohacoDatabase = IrohacoDatabaseFactory.open(context).also { db = it }

    private fun SupportSQLiteDatabase.count(type: String, excludeInternal: Boolean): Int =
        single(
            "SELECT count(*) FROM sqlite_master WHERE type = '$type'" +
                if (excludeInternal) " AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')" +
                    // The derived full-text indexes (M2-07, M2-11) are not part of schema.sql.
                    " AND name NOT LIKE 'ocr_fts%' AND name NOT LIKE 'annotation_fts%'" else "",
        ).toInt()

    private fun SupportSQLiteDatabase.single(query: String): String =
        query(query).use { it.moveToFirst(); it.getString(0) }
}
