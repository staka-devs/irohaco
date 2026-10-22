package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.IndexJobProgress
import dev.irohaco.core.contracts.IndexJobState
import dev.irohaco.core.contracts.ModelArtifact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Job items are checkpoints: a vector is stored once per space, target and revision (M2-03, NFR-004). */
@RunWith(RobolectricTestRunner::class)
class RoomIndexJobStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val store = RoomIndexJobStore(db)
    private val sql get() = db.openHelper.writableDatabase
    private val a = IndexItem("ta", "r1")
    private val b = IndexItem("tb", "r1")

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private suspend fun setUp() {
        RoomModelProfiles(db).register(ModelArtifact("m", "Model", "https://example.invalid/m", 1, "d"))
        sql.execSQL(
            """INSERT INTO embedding_spaces(space_id, model_profile_id, dimension, normalization, preprocessing_signature, space_signature, lifecycle_state)
               VALUES ('s', 'm', 2, 'L2', 'p', 'sig', 'BUILDING')""",
        )
        for (t in listOf("a", "b")) {
            sql.execSQL("INSERT INTO media_assets(asset_id, media_kind, first_seen_at) VALUES ('$t', 'IMAGE', 0)")
            sql.execSQL("INSERT INTO embedding_targets(target_id, asset_id) VALUES ('t$t', '$t')")
        }
        store.create("j", "s", IndexJobKind.INITIAL, true, 0, listOf(a, b, a))
    }

    private fun count(query: String) = sql.query(query).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun `items commit once with their vector and progress counts them`() = runTest {
        setUp()
        assertEquals(IndexJobProgress(IndexJobState.PLANNED, 2, 0, 0, 0), store.progress("j").first())
        assertEquals(true, store.transition("j", setOf(IndexJobState.PLANNED), IndexJobState.RUNNING))
        assertEquals(false, store.transition("j", setOf(IndexJobState.PLANNED), IndexJobState.RUNNING))
        assertEquals(a, store.nextPending("j"))
        store.begin("j", a)
        store.commit("j", a, "v1", floatArrayOf(0.6f, 0.8f), 0)
        store.commit("j", a, "v2", floatArrayOf(0.6f, 0.8f), 0)
        assertEquals(1, count("SELECT count(*) FROM embedding_vectors"))
        assertEquals(b, store.nextPending("j"))
        store.begin("j", b)
        store.fail("j", b)
        assertNull(store.nextPending("j"))
        assertEquals(IndexJobProgress(IndexJobState.RUNNING, 0, 1, 1, 0), store.progress("j").first())
        store.retryFailed("j")
        assertEquals(b, store.nextPending("j"))
    }

    @Test
    fun `a vector of the wrong size is refused and nothing commits`() = runTest {
        setUp()
        assertFailsWith<Exception> { store.commit("j", a, "v", floatArrayOf(1f), 0) }
        assertEquals(a, store.nextPending("j"))
    }

    @Test
    fun `after a crash running items go back to the queue, or fail after too many attempts`() = runTest {
        setUp()
        store.transition("j", setOf(IndexJobState.PLANNED), IndexJobState.RUNNING)
        repeat(3) { store.begin("j", a) }
        store.begin("j", b)
        store.requeueRunning(null, 3)
        store.pauseInterrupted()
        assertEquals(IndexJobState.PAUSED, store.state("j"))
        assertEquals(IndexJobProgress(IndexJobState.PAUSED, 1, 0, 1, 0), store.progress("j").first())
    }

    @Test
    fun `a committed item without its vector fails verification`() = runTest {
        setUp()
        store.commit("j", a, "v", floatArrayOf(0.6f, 0.8f), 0)
        sql.execSQL("DELETE FROM embedding_vectors")
        assertEquals(1, store.failUnstored("j"))
    }

    @Test
    fun `the latest job and its failed files are listed`() = runTest {
        assertNull(store.latest().first())
        setUp()
        sql.execSQL("INSERT INTO storage_volumes(volume_id, provider_volume_name, state) VALUES ('v', 'external_primary', 'ONLINE')")
        sql.execSQL(
            """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, mime_type, file_size_bytes, access_state)
               VALUES ('sb', 'b', 'v', 1, 'content://b', 'IMG_0002.jpg', 'image/jpeg', 1, 'AVAILABLE')""",
        )
        store.create("k", "s", IndexJobKind.INCREMENTAL, true, 1, listOf(b))
        assertEquals("k", store.latest().first())
        store.fail("k", b)
        assertEquals(listOf("IMG_0002.jpg"), store.failedNames("k", 50).first())
    }

    @Test
    fun `an initial job needs the user's request`() = runTest {
        setUp()
        assertFailsWith<Exception> { store.create("k", "s", IndexJobKind.INITIAL, false, 0, emptyList()) }
    }
}
