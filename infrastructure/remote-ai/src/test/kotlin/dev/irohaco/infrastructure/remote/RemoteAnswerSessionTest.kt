package dev.irohaco.infrastructure.remote

import com.sun.net.httpserver.HttpServer
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteFailure
import dev.irohaco.core.contracts.RemoteProtocol
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contract tests of the three generation formats against a server in the test, never a real API
 * (ADR-0032): what is sent, how the answer is read, and how failures come back. Spike G is the case
 * where the grants are off and the server must see nothing.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteAnswerSessionTest {
    private class Request(val path: String, val headers: Map<String, String>, val body: JSONObject)

    private val requests = mutableListOf<Request>()
    private var status = 200
    private var reply = ""
    private var replyHeaders = mapOf<String, String>()
    private var stallMs = 0L
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests += Request(
                exchange.requestURI.path,
                exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() },
                JSONObject(exchange.requestBody.bufferedReader().readText()),
            )
            Thread.sleep(stallMs)
            replyHeaders.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
    private var asked = emptySet<EgressPayload>()

    @After
    fun tearDown() = server.stop(0)

    private fun session(protocol: RemoteProtocol, streaming: Boolean = false, granted: Boolean = true, timeoutMs: Int = 5_000) =
        RemoteAnswerSession(protocol, baseUrl, "sk-test-secret-key", "model-x", streaming, { payloads ->
            asked = payloads
            if (granted) EgressDecision.Allowed else EgressDecision.Denied(payloads, emptySet())
        }, timeoutMs)

    @Test
    fun `chat completions sends the photos and the question and reads the reply`() = runTest {
        reply = """{"choices":[{"message":{"role":"assistant","content":"A beach [1]"}}]}"""
        assertEquals("A beach [1]", session(RemoteProtocol.OPENAI_CHAT).answer("Be brief.", listOf(jpeg), "Where?"))
        val request = requests.single()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test-secret-key", request.headers["authorization"])
        val messages = request.body.getJSONArray("messages")
        assertEquals("Be brief.", messages.getJSONObject(0).getString("content"))
        val content = messages.getJSONObject(1).getJSONArray("content")
        assertTrue(content.getJSONObject(0).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,/9g"))
        assertEquals("Where?", content.getJSONObject(1).getString("text"))
        assertEquals(setOf(EgressPayload.QUERY_TEXT, EgressPayload.IMAGE_PIXELS), asked)
    }

    @Test
    fun `chat completions streams the reply as it grows`() = runTest {
        reply = sse(
            null to """{"choices":[{"delta":{"role":"assistant"}}]}""",
            null to """{"choices":[{"delta":{"content":"A "}}]}""",
            null to """{"choices":[{"delta":{"content":"beach"}}]}""",
            null to "[DONE]",
        )
        val seen = mutableListOf<String>()
        assertEquals("A beach", session(RemoteProtocol.OPENAI_CHAT, streaming = true).answer("i", emptyList(), "q") { seen += it })
        assertEquals(listOf("A ", "A beach"), seen)
        assertEquals(true, requests.single().body.getBoolean("stream"))
        assertEquals(setOf(EgressPayload.QUERY_TEXT), asked)
    }

    @Test
    fun `responses sends instructions and input and reads the output text`() = runTest {
        reply = """{"output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"A park"}]}]}"""
        assertEquals("A park", session(RemoteProtocol.OPENAI_RESPONSES).answer("Be brief.", listOf(jpeg), "Where?"))
        val request = requests.single()
        assertEquals("/v1/responses", request.path)
        assertEquals("Be brief.", request.body.getString("instructions"))
        val content = request.body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_image", content.getJSONObject(0).getString("type"))
        assertEquals("Where?", content.getJSONObject(1).getString("text"))
    }

    @Test
    fun `responses streams only the output text deltas`() = runTest {
        reply = sse(
            "response.created" to """{"type":"response.created"}""",
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"A "}""",
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"park"}""",
            "response.completed" to """{"type":"response.completed"}""",
        )
        assertEquals("A park", session(RemoteProtocol.OPENAI_RESPONSES, streaming = true).answer("i", emptyList(), "q"))
    }

    @Test
    fun `anthropic messages sends the key in its own header and the image as base64`() = runTest {
        reply = """{"content":[{"type":"text","text":"A cat"}]}"""
        assertEquals("A cat", session(RemoteProtocol.ANTHROPIC_MESSAGES).answer("Be brief.", listOf(jpeg), "What?"))
        val request = requests.single()
        assertEquals("/v1/messages", request.path)
        assertEquals("sk-test-secret-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals("Be brief.", request.body.getString("system"))
        val image = request.body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("image/jpeg", image.getJSONObject("source").getString("media_type"))
    }

    @Test
    fun `anthropic messages streams the text deltas`() = runTest {
        reply = sse(
            "message_start" to """{"type":"message_start"}""",
            "content_block_delta" to """{"type":"content_block_delta","delta":{"type":"text_delta","text":"A "}}""",
            "ping" to """{"type":"ping"}""",
            "content_block_delta" to """{"type":"content_block_delta","delta":{"type":"text_delta","text":"cat"}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        assertEquals("A cat", session(RemoteProtocol.ANTHROPIC_MESSAGES, streaming = true).answer("i", emptyList(), "q"))
    }

    @Test
    fun `a rate limit comes back with when to try again`() = runTest {
        status = 429
        replyHeaders = mapOf("Retry-After" to "12")
        reply = """{"error":{"message":"slow down"}}"""
        for (protocol in GENERATING) {
            val e = assertFailsWith<RemoteCallException> { session(protocol).answer("i", emptyList(), "q") }
            assertEquals(RemoteFailure.RateLimited(12), e.failure)
        }
    }

    @Test
    fun `a refused key is told apart and never echoed back`() = runTest {
        status = 401
        reply = """{"error":{"message":"Incorrect API key provided: sk-test-secret-key"}}"""
        for (protocol in GENERATING) {
            val e = assertFailsWith<RemoteCallException> { session(protocol).answer("i", emptyList(), "q") }
            assertEquals(RemoteFailure.Unauthorized, e.failure)
            assertTrue("sk-test-secret-key" !in e.message.orEmpty())
        }
        status = 500
        val e = assertFailsWith<RemoteCallException> { session(RemoteProtocol.OPENAI_CHAT).answer("i", emptyList(), "q") }
        assertTrue("sk-test-secret-key" !in (e.failure as RemoteFailure.Failed).message)
    }

    @Test
    fun `a server that does not answer in time is a timeout`() = runTest {
        stallMs = 1_500
        val e = assertFailsWith<RemoteCallException> { session(RemoteProtocol.ANTHROPIC_MESSAGES, timeoutMs = 300).answer("i", emptyList(), "q") }
        assertEquals(RemoteFailure.Timeout, e.failure)
    }

    @Test
    fun `spike G - with the grants off nothing reaches the server`() = runTest {
        for (protocol in GENERATING) {
            assertFailsWith<EgressDeniedException> { session(protocol, granted = false).answer("i", listOf(jpeg), "q") }
            session(protocol, granted = false).warm()
        }
        assertEquals(0, requests.size)
    }

    private fun sse(vararg events: Pair<String?, String>) =
        events.joinToString("") { (event, data) -> (event?.let { "event: $it\n" } ?: "") + "data: $data\n\n" }

    private companion object {
        val GENERATING = listOf(RemoteProtocol.OPENAI_CHAT, RemoteProtocol.OPENAI_RESPONSES, RemoteProtocol.ANTHROPIC_MESSAGES)
    }
}
