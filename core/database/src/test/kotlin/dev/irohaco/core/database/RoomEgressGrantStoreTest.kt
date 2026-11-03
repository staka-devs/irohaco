package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressPayload.IMAGE_PIXELS
import dev.irohaco.core.contracts.EgressPayload.QUERY_TEXT
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.TaskType.RAG
import dev.irohaco.core.contracts.TaskType.SEARCH_QUERY
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Grants are kept per connection and shared by its models (ARCHITECTURE §9.2). */
@RunWith(RobolectricTestRunner::class)
class RoomEgressGrantStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val store = RoomEgressGrantStore(db) { 1 }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private suspend fun remote() {
        db.modelProfileDao().ensureLocalConnection() // the local runtime, which is not listed
        db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO provider_connections VALUES ('openai', 'OPENAI_RESPONSES', 'https://api.example', 'key')")
            execSQL("INSERT INTO model_profiles VALUES ('gpt', 'openai', 'gpt-x', NULL, 'GPT')")
            execSQL("INSERT INTO model_profiles VALUES ('gpt-mini', 'openai', 'gpt-x-mini', NULL, 'GPT mini')")
        }
    }

    @Test
    fun `only remote connections are listed`() = runTest {
        remote()
        assertEquals(listOf(RemoteConnection("openai", "OPENAI_RESPONSES", "https://api.example")), store.connections.first())
    }

    @Test
    fun `a grant reaches every model of its connection, for its task only, until taken back`() = runTest {
        remote()
        assertEquals(emptySet(), store.granted("gpt", RAG))
        store.set(EgressGrant("openai", RAG, QUERY_TEXT), true)
        store.set(EgressGrant("openai", RAG, IMAGE_PIXELS), true)
        store.set(EgressGrant("openai", RAG, IMAGE_PIXELS), true) // twice is still one grant
        assertEquals(setOf(QUERY_TEXT, IMAGE_PIXELS), store.granted("gpt-mini", RAG))
        assertEquals(emptySet(), store.granted("gpt", SEARCH_QUERY))
        store.set(EgressGrant("openai", RAG, IMAGE_PIXELS), false)
        assertEquals(setOf(EgressGrant("openai", RAG, QUERY_TEXT)), store.grants.first())
    }
}
