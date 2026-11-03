package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressPayload.IMAGE_PIXELS
import dev.irohaco.core.contracts.EgressPayload.QUERY_TEXT
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteModel
import dev.irohaco.core.contracts.RemoteProtocol
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
        store.add("openai", RemoteProtocol.OPENAI_RESPONSES, "https://api.example", "ref")
        db.modelProfileDao().ensureLocalConnection() // the local runtime, which is not listed
        db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO model_profiles VALUES ('gpt', 'openai', 'gpt-x', NULL, 'GPT')")
            execSQL("INSERT INTO model_profiles VALUES ('gpt-mini', 'openai', 'gpt-x-mini', NULL, 'GPT mini')")
        }
    }

    @Test
    fun `only remote connections are listed`() = runTest {
        remote()
        assertEquals(listOf(RemoteConnection("openai", RemoteProtocol.OPENAI_RESPONSES, "https://api.example")), store.connections.first())
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

    @Test
    fun `removing a connection takes its grants and hands back its key's reference`() = runTest {
        store.add("anthropic", RemoteProtocol.ANTHROPIC_MESSAGES, "https://api.example", "ref-a")
        store.set(EgressGrant("anthropic", RAG, QUERY_TEXT), true)
        assertEquals("ref-a", store.remove("anthropic"))
        assertEquals(emptyList(), store.connections.first())
        assertEquals(emptySet(), store.grants.first())
    }

    @Test
    fun `a remote model keeps its tested capabilities and goes with its connection`() = runTest {
        store.add("anthropic", RemoteProtocol.ANTHROPIC_MESSAGES, "https://api.example", "ref-a")
        val claude = RemoteModel("p1", "anthropic", "claude-x", "Claude", setOf(ModelCapability.INPUT_TEXT, ModelCapability.INPUT_IMAGE, ModelCapability.OUTPUT_TEXT))
        store.addModel(claude)
        assertEquals(listOf(claude), store.models.first())
        assertEquals(claude, store.model("p1"))
        assertEquals("ref-a", store.remove("anthropic"))
        assertEquals(emptyList(), store.models.first())
    }

    @Test
    fun `a removed model leaves its connection`() = runTest {
        remote()
        store.addModel(RemoteModel("p2", "openai", "gpt-y", "GPT y", setOf(ModelCapability.OUTPUT_TEXT)))
        store.removeModel("p2")
        assertEquals(listOf("gpt", "gpt-mini"), store.models.first().map { it.profileId }.sorted())
    }
}
