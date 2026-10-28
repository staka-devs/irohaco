package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.VideoQuality
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Counting and planning stay linear on a large library. Per-asset source lookups once took the
 * access_state index and read every source for each asset, so the settings screen stayed preparing.
 */
@RunWith(RobolectricTestRunner::class)
class PendingCountScaleTest {
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

    @Test
    fun `the current revision is looked up by asset`() {
        val plan = explain("SELECT $REVISION FROM media_assets a")
        assertTrue(plan.any { "ix_media_sources_asset" in it }, plan.joinToString("\n"))
        assertTrue(plan.none { "ix_media_sources_access" in it }, plan.joinToString("\n"))
    }

    @Test
    fun `counts and plans a large library in seconds`() = runTest {
        RoomModelProfiles(db).register(ModelArtifact("m", "Model", "https://example.invalid/m", 1, "d"))
        sql.execSQL("INSERT INTO storage_volumes(volume_id, provider_volume_name, state) VALUES ('v', 'external_primary', 'ONLINE')")
        sql.beginTransaction()
        try {
            for (i in 0 until ASSETS) {
                val video = i % 10 == 0
                sql.execSQL("INSERT INTO media_assets(asset_id, media_kind, first_seen_at) VALUES ('a$i', '${if (video) "VIDEO" else "IMAGE"}', 0)")
                sql.execSQL(
                    """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, relative_path, mime_type,
                         file_size_bytes, observed_modified_at, observed_duration_ms, access_state)
                       VALUES ('s$i', 'a$i', 'v', $i, 'content://$i', '$i.jpg', 'DCIM/Camera/', 'image/jpeg', 10, 5, ${if (video) 25_000 else "NULL"}, 'AVAILABLE')""",
                )
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        val space = store.ensureSpace("s1", spec, "sig")

        assertFast { assertEquals(ASSETS, store.pendingCount(space)) }
        val items = assertFast { store.plan(space, null, VideoQuality.BALANCED, 0) }
        // Half of everything indexed, so the vector checks have rows to look through.
        sql.beginTransaction()
        try {
            items.forEachIndexed { i, item ->
                if (i % 2 == 0) {
                    sql.execSQL(
                        "INSERT INTO embedding_vectors(vector_id, space_id, target_id, source_revision_digest, vector_blob, created_at) VALUES (?, ?, ?, ?, x'0000000000000000', 0)",
                        arrayOf<Any>("vec$i", space, item.targetId, item.sourceRevisionDigest),
                    )
                }
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        assertFast { store.pendingCount(space) }
    }

    private inline fun <T> assertFast(block: () -> T): T {
        val start = System.currentTimeMillis()
        return block().also {
            val took = System.currentTimeMillis() - start
            assertTrue(took < LIMIT_MS, "took ${took}ms for $ASSETS assets")
        }
    }

    private fun explain(query: String): List<String> = sql.query("EXPLAIN QUERY PLAN $query").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(3)) }
    }

    private companion object {
        const val ASSETS = 20_000

        // Linear work is well under a second here; the quadratic plans took minutes.
        const val LIMIT_MS = 10_000L
    }
}
