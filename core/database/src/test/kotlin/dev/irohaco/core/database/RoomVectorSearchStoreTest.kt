package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.IndexJobState
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.VectorHit
import dev.irohaco.core.contracts.VideoQuality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Spaces turn READY once built, one is the default, and search reads only its own vectors (M2-08, ADR-0003). */
@RunWith(RobolectricTestRunner::class)
class RoomVectorSearchStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val store = RoomVectorSearchStore(db)
    private val media = RoomMediaIndexStore(db)
    private val jobs = RoomIndexJobStore(db)
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
        for (id in listOf("a", "b", "c")) {
            sql.execSQL("INSERT INTO media_assets(asset_id, media_kind, first_seen_at) VALUES ('$id', 'IMAGE', 0)")
            sql.execSQL(
                """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, mime_type,
                     file_size_bytes, observed_modified_at, access_state)
                   VALUES ('s$id', '$id', 'v', ${id[0].code}, 'content://$id', '$id', 'image/jpeg', 10, 5, 'AVAILABLE')""",
            )
        }
    }

    /** Builds [space] with one vector per asset, as an indexing job would. */
    private suspend fun build(space: String, vectors: Map<String, FloatArray>) {
        val items = media.plan(space, null, VideoQuality.BALANCED, 0).filter { it.targetId.removePrefix("asset:") in vectors }
        val job = "job-$space"
        jobs.create(job, space, IndexJobKind.INITIAL, true, 0, items)
        jobs.transition(job, setOf(IndexJobState.PLANNED), IndexJobState.SUCCEEDED)
        items.forEach { jobs.commit(job, it, "v-$space-${it.targetId}", vectors.getValue(it.targetId.removePrefix("asset:")), 0) }
        jobs.markSpaceReady(job)
    }

    @Test
    fun `a built space becomes READY and the first one the default`() = runTest {
        setUp()
        val a = media.ensureSpace("A", spec, "sig-a")
        val b = media.ensureSpace("B", spec.copy(preprocessingSignature = "p2"), "sig-b")
        assertEquals(listOf(SpaceState.BUILDING, SpaceState.BUILDING), store.spaces().first().map { it.state })
        assertEquals(false, store.setDefault(b))

        build(a, mapOf("a" to floatArrayOf(1f, 0f)))
        build(b, mapOf("a" to floatArrayOf(0f, 1f)))
        assertEquals(listOf(true to SpaceState.READY, false to SpaceState.READY), store.spaces().first().map { it.isDefault to it.state })

        // Switching keeps both spaces and their vectors.
        assertEquals(true, store.setDefault(b))
        assertEquals(listOf(false, true), store.spaces().first().map { it.isDefault })
        assertEquals(2, sql.query("SELECT count(*) FROM embedding_vectors").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun `search returns the nearest files of the space, available and current only`() = runTest {
        setUp()
        val a = media.ensureSpace("A", spec, "sig-a")
        val other = media.ensureSpace("B", spec.copy(preprocessingSignature = "p2"), "sig-b")
        build(a, mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0.6f, 0.8f), "c" to floatArrayOf(0f, 1f)))
        build(other, mapOf("c" to floatArrayOf(1f, 0f)))

        assertEquals(listOf("a" to 1f, "b" to 0.6f, "c" to 0f), store.nearest(a, floatArrayOf(1f, 0f), 10).map { it.assetId to it.similarity })
        assertEquals(listOf(VectorHit("c", null, 1f)), store.nearest(a, floatArrayOf(0f, 1f), 1))
        assertEquals(listOf("c"), store.nearest(other, floatArrayOf(1f, 0f), 10).map { it.assetId })

        sql.execSQL("UPDATE media_sources SET access_state = 'INACCESSIBLE' WHERE asset_id = 'a'")
        sql.execSQL("UPDATE media_sources SET observed_modified_at = 6 WHERE asset_id = 'b'")
        assertEquals(listOf("c"), store.nearest(a, floatArrayOf(1f, 0f), 10).map { it.assetId })
        assertEquals(0.5f, store.coverage(a))
    }

    @Test
    fun `a stale space keeps its vectors, loses the default and its open jobs`() = runTest {
        setUp()
        val a = media.ensureSpace("A", spec, "sig-a")
        build(a, mapOf("a" to floatArrayOf(1f, 0f)))
        jobs.create("open", a, IndexJobKind.INCREMENTAL, true, 1, listOf(IndexItem("asset:b", "10:5")))
        store.markStale(listOf(a))
        assertEquals(SpaceState.STALE, store.space(a)?.state)
        assertEquals(false, store.space(a)?.isDefault)
        assertEquals(IndexJobState.CANCELLED, jobs.state("open"))
        assertEquals(1f / 3, store.coverage(a))
        assertEquals(listOf(IndexItem("asset:b", "10:5"), IndexItem("asset:c", "10:5")), media.plan(a, null, VideoQuality.BALANCED, 0))
    }

    @Test
    fun `a space reads back the settings its signature was made from`() = runTest {
        setUp()
        val a = media.ensureSpace("A", spec, "sig-a")
        assertEquals(spec, store.space(a)?.specWith("d"))
    }
}
