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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** A search by meaning reads each vector once, so a large library is searched in about a second. */
@RunWith(RobolectricTestRunner::class)
class VectorSearchScaleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val sql get() = db.openHelper.writableDatabase

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    @Test
    fun `a large library is searched in one pass`() = runTest(timeout = 10.minutes) {
        RoomModelProfiles(db).register(ModelArtifact("m", "Model", "https://example.invalid/m", 1, "d"))
        sql.execSQL("INSERT INTO storage_volumes(volume_id, provider_volume_name, state) VALUES ('v', 'external_primary', 'ONLINE')")
        sql.beginTransaction()
        try {
            for (i in 0 until ASSETS) {
                sql.execSQL("INSERT INTO media_assets(asset_id, media_kind, first_seen_at) VALUES ('a$i', 'IMAGE', 0)")
                sql.execSQL(
                    """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, mime_type,
                         file_size_bytes, observed_modified_at, access_state)
                       VALUES ('s$i', 'a$i', 'v', $i, 'content://$i', '$i.jpg', 'image/jpeg', 10, 5, 'AVAILABLE')""",
                )
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        val media = RoomMediaIndexStore(db)
        val space = media.ensureSpace("s1", EmbeddingSpaceSpec("m", "d", DIMENSION, "L2", "q: ", null, "p"), "sig")
        val random = Random(1)
        val items = media.plan(space, null, VideoQuality.BALANCED, 0)
        val query = FloatArray(DIMENSION) { 0.01f }
        val scores = mutableMapOf<String, Float>()
        sql.beginTransaction()
        try {
            items.forEachIndexed { i, item ->
                val vector = FloatArray(DIMENSION) { random.nextFloat() - 0.5f }
                var score = 0f
                for (d in vector.indices) score += vector[d] * query[d]
                scores[item.targetId.removePrefix("asset:")] = score
                val blob = ByteBuffer.allocate(4 * DIMENSION).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(vector) }.array()
                sql.execSQL(
                    "INSERT INTO embedding_vectors(vector_id, space_id, target_id, source_revision_digest, vector_blob, created_at) VALUES (?, ?, ?, ?, ?, 0)",
                    arrayOf<Any>("vec$i", space, item.targetId, item.sourceRevisionDigest, blob),
                )
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }

        val start = System.currentTimeMillis()
        val hits = RoomVectorSearchStore(db).nearest(space, query, 200)
        val took = System.currentTimeMillis() - start
        // Every page is read: the best 200 of all vectors, not of the first pages.
        assertEquals(scores.entries.sortedByDescending { it.value }.take(200).map { it.key }, hits.map { it.assetId })
        assertTrue(took < LIMIT_MS, "took ${took}ms for $ASSETS vectors")
    }

    private companion object {
        const val ASSETS = 30_000
        const val DIMENSION = 768

        // A single pass took about 0.5 s here; the cursor that re-ran its query for every window took 5 s.
        const val LIMIT_MS = 2_500L
    }
}
