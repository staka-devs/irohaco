package dev.irohaco

import android.Manifest
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.irohaco.core.application.IndexJobs
import dev.irohaco.core.application.PlanMediaIndex
import dev.irohaco.core.application.SearchByMeaning
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.IndexJobState
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.database.RoomIndexJobStore
import dev.irohaco.core.database.RoomMediaIndexStore
import dev.irohaco.core.database.RoomModelProfiles
import dev.irohaco.core.database.RoomVectorSearchStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.sqrt

/**
 * Spike C (docs/verification/AI_SEARCH_SPIKE.md, ADR-0003): two embedding spaces of the same photos live
 * side by side in the app's SQLite on a device. A space still being built leaves the other searchable,
 * switching the default needs no rebuild, and a newer model file retires the old space. The model is
 * replaced by fixed vectors per model, so CI runs it; the times are logged under the tag SpikeC.
 */
@RunWith(AndroidJUnit4::class)
class SpikeCEmbeddingSpacesTest {
    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Its own database, so the app's catalog and the other tests are untouched.
    private val dbName = "spike-c-${UUID.randomUUID()}.db"
    private val container = AppContainer(context, dbName)
    private val created = mutableListOf<Uri>()

    @After
    fun tearDown() {
        created.forEach { context.contentResolver.delete(it, null, null) }
        container.database.close()
        context.deleteDatabase(dbName)
    }

    private val modelA = ModelArtifact("model-a", "Model A", "https://example.invalid/a", 1, "digest-a")
    private val modelB = ModelArtifact("model-b", "Model B", "https://example.invalid/b", 1, "digest-b")
    private fun spec(model: ModelArtifact) = EmbeddingSpaceSpec(model.id, model.sha256, DIM, "L2", null, null, "p")

    /** A fixed unit vector per model and target, as a model would give the same file the same vector. */
    private fun vector(model: ModelArtifact, targetId: String): FloatArray {
        val random = java.util.Random((model.id + targetId).hashCode().toLong())
        val v = FloatArray(DIM) { random.nextFloat() - 0.5f }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(DIM) { v[it] / norm }
    }

    private fun jobs(model: ModelArtifact) = IndexJobs(
        RoomIndexJobStore(container.database),
        { item -> vector(model, item.targetId) },
        { null },
        { UUID.randomUUID().toString() },
        System::currentTimeMillis,
    )

    private fun plan(model: ModelArtifact, jobs: IndexJobs) =
        PlanMediaIndex(RoomMediaIndexStore(container.database), jobs, { UUID.randomUUID().toString() }, System::currentTimeMillis, spec(model))

    @Test
    fun twoSpacesLiveSideBySideAndSwitchWithoutARebuild() = runBlocking {
        repeat(3) { created += insertTestImage(context) }
        container.syncCatalog()
        val profiles = RoomModelProfiles(container.database)
        profiles.register(modelA)
        profiles.register(modelB)
        val store = RoomVectorSearchStore(container.database)
        val jobsA = jobs(modelA)
        val jobsB = jobs(modelB)

        // A is built and becomes the default.
        val jobA = plan(modelA, jobsA)()
        assertEquals(IndexJobState.SUCCEEDED, jobsA.run(jobA))
        val spaceA = store.spaces().first().single()
        assertEquals(SpaceState.READY to true, spaceA.state to spaceA.isDefault)
        // A photo of the space; a video's frames have targets of their own.
        val target = store.nearest(spaceA.spaceId, vector(modelA, "asset:x"), 100).first { it.timestampMs == null && !it.fromMemo }.assetId
        val queryA = vector(modelA, "asset:$target")

        // B is planned but not built: A still answers, B covers nothing yet (AC-005).
        val jobB = plan(modelB, jobsB)()
        val building = store.spaces().first().single { it.spaceId != spaceA.spaceId }
        assertEquals(SpaceState.BUILDING, building.state)
        assertEquals(0f, store.coverage(building.spaceId))
        val searchA = SearchByMeaning(store, embedText = { queryA }, modelDigest = modelA.sha256)
        assertEquals(target, searchA("q")!!.hits.first().assetId)

        // B is built; both are READY and switching the default is one transaction, no new job (AC-004).
        assertEquals(IndexJobState.SUCCEEDED, jobsB.run(jobB))
        val spaceB = store.space(building.spaceId)!!
        assertEquals(SpaceState.READY, spaceB.state)
        val vectorsBefore = vectorCount()
        val switchMs = measure { assertTrue(store.setDefault(spaceB.spaceId)) }
        assertEquals(spaceB.spaceId, store.spaces().first().single { it.isDefault }.spaceId)
        val searchB = SearchByMeaning(store, embedText = { vector(modelB, "asset:$target") }, modelDigest = modelB.sha256)
        var hitB: String? = null
        val firstQueryMs = measure { hitB = searchB("q")?.hits?.first()?.assetId }
        assertEquals(target, hitB)
        assertTrue(store.setDefault(spaceA.spaceId))
        assertEquals(vectorsBefore, vectorCount())
        Log.i(TAG, "switch ${switchMs}ms, first query in the new space ${firstQueryMs}ms, vectors $vectorsBefore")

        // A newer model file retires A; B keeps its vectors and stays searchable.
        searchB.retireOldSpaces()
        assertEquals(SpaceState.STALE, store.space(spaceA.spaceId)!!.state)
        assertNotNull(searchB("q", spaceB.spaceId))
        assertEquals(target, searchB("q", spaceB.spaceId)!!.hits.first().assetId)
    }

    private fun vectorCount() =
        container.database.openHelper.readableDatabase.query("SELECT count(*) FROM embedding_vectors").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun measure(block: suspend () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private companion object {
        const val TAG = "SpikeC"
        const val DIM = 8
    }
}
