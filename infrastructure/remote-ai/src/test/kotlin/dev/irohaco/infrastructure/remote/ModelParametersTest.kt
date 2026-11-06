package dev.irohaco.infrastructure.remote

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
