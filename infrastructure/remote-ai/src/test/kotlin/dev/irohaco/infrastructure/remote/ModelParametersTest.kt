package dev.irohaco.infrastructure.remote

import dev.irohaco.core.contracts.ListedModel
import dev.irohaco.core.contracts.ModelKind
import dev.irohaco.core.contracts.ModelOptions
import dev.irohaco.core.contracts.RemoteProtocol
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The user's request fields go in only where the protocol defines them, and models.dev is read as it lists models. */
@RunWith(RobolectricTestRunner::class)
class ModelParametersTest {
    @Test
    fun `values go in at their dotted paths, numbers as numbers`() {
        val body = AnthropicMessages.body("m", "i", emptyList(), "q", false)
        withParameters(body, RemoteProtocol.ANTHROPIC_MESSAGES, mapOf("thinking.type" to "enabled", "thinking.budget_tokens" to "2048", "max_tokens" to "8000"))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(2048L, body.getJSONObject("thinking").get("budget_tokens"))
        assertEquals(8000L, body.get("max_tokens"))
    }

    @Test
    fun `fields the protocol does not define, and numbers that are not numbers, are left out`() {
        val body = Responses.body("m", "i", emptyList(), "q", false)
        withParameters(body, RemoteProtocol.OPENAI_RESPONSES, mapOf("reasoning.effort" to "high", "model" to "other", "input" to "x"))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("m", body.getString("model"))
        assertEquals(1, body.getJSONArray("input").length())
        val anthropic = withParameters(AnthropicMessages.body("m", "i", emptyList(), "q", false), RemoteProtocol.ANTHROPIC_MESSAGES, mapOf("max_tokens" to "lots"))
        assertEquals(1024, anthropic.get("max_tokens"))
    }

    @Test
    fun `a service's list is read in its own format, and models dev says what each model is`() {
        val openai = ServiceModelLister.parse(JSONObject("""{"data": [{"id": "gpt-x"}, {"id": "text-embed-y"}, {"id": "whisper"}]}"""))
        val dev = JSONObject(
            """{"openai": {"id": "openai", "models": {
                "gpt-x": {"id": "gpt-x", "name": "GPT X", "modalities": {"input": ["text", "image"]}},
                "whisper": {"id": "whisper", "type": "transcription"}}}}""",
        )
        assertEquals(
            listOf(
                ListedModel("gpt-x", "GPT X", ModelKind.CHAT, true),
                ListedModel("text-embed-y", null, ModelKind.EMBEDDING),
                ListedModel("whisper", null, ModelKind.OTHER, null),
            ),
            ServiceModelLister.merge(openai, dev, "https://api.openai.com/v1"),
        )
        val gemini = ServiceModelLister.parse(
            JSONObject(
                """{"models": [
                    {"name": "models/gemini-embedding-001", "displayName": "Gemini Embedding", "supportedGenerationMethods": ["embedContent"]},
                    {"name": "models/gemini-x", "supportedGenerationMethods": ["generateContent"]}]}""",
            ),
        )
        assertEquals(listOf(ListedModel("gemini-embedding-001", "Gemini Embedding", ModelKind.EMBEDDING), ListedModel("gemini-x", null, ModelKind.CHAT)), gemini)
        assertEquals(listOf(ListedModel("gpt-x", "GPT X", ModelKind.CHAT, true), ListedModel("whisper", null, ModelKind.OTHER, null)), ServiceModelLister.fromModelsDev(dev, "https://api.openai.com/v1"))
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", ServiceModelLister.defaultEndpoint(RemoteProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1/"))
    }

    @Test
    fun `models dev is read from the provider at the address first`() {
        val list = JSONObject(
            """{
              "reseller": {"id": "reseller", "api": "https://example.com/v1", "models": {"gpt-x": {"limit": {"output": 10}}}},
              "openai": {"id": "openai", "models": {"gpt-x": {
                "reasoning_options": [{"type": "effort", "values": ["low", "high"]}], "limit": {"context": 100, "output": 50}}}},
              "anthropic": {"id": "anthropic", "models": {"claude-x": {
                "reasoning_options": [{"type": "toggle"}, {"type": "budget_tokens", "min": 1024}]}}}
            }""",
        )
        assertEquals(ModelOptions(listOf("low", "high"), null, 50), ModelsDev.find(list, "https://api.openai.com/v1", "gpt-x"))
        assertEquals(ModelOptions(null, null, 10), ModelsDev.find(list, "https://example.com/v1", "gpt-x"))
        assertEquals(ModelOptions(null, 1024, null), ModelsDev.find(list, "http://192.168.0.2:8080/v1", "claude-x"))
        assertNull(ModelsDev.find(list, "https://api.openai.com/v1", "unknown"))
        // As services name the same model.
        assertEquals(ModelOptions(listOf("low", "high"), null, 50), ModelsDev.find(list, "https://api.openai.com/v1", "openai/GPT-X"))
        assertEquals(ModelOptions(null, 1024, null), ModelsDev.find(list, "https://api.anthropic.com/v1", "claude-x-20260101"))
        assertEquals(ModelOptions(null, 1024, null), ModelsDev.find(list, "https://api.anthropic.com/v1", "claude-x-latest"))
    }
}
