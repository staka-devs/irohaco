package dev.irohaco.core.application

import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceNameTest {
    private fun at(url: String, name: String? = null) = RemoteConnection("c", RemoteProtocol.OPENAI_CHAT, url, name).serviceName

    @Test
    fun `a well-known address is called by its company, any other by its host`() {
        assertEquals("Anthropic", at("https://api.anthropic.com/v1"))
        assertEquals("OpenAI", at("https://api.openai.com/v1"))
        assertEquals("Google", at("https://generativelanguage.googleapis.com/v1beta"))
        assertEquals("llm.example.com", at("https://llm.example.com/v1"))
    }

    @Test
    fun `the user's name comes first, and a blank one gives way to the suggestion`() {
        assertEquals("My server", at("https://llm.example.com/v1", "My server"))
        assertEquals("OpenAI", at("https://api.openai.com/v1", " "))
    }
}
