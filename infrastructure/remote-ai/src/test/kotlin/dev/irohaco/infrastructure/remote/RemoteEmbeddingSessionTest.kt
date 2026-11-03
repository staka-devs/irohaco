package dev.irohaco.infrastructure.remote

import com.sun.net.httpserver.HttpServer
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteFailure
import dev.irohaco.core.contracts.RemoteProtocol
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Contract tests of the two embedding formats against a server in the test, never a real API (ADR-0032). */
@RunWith(RobolectricTestRunner::class)
class RemoteEmbeddingSessionTest {
    private class Request(val path: String, val headers: Map<String, String>, val body: JSONObject)

    private val requests = mutableListOf<Request>()
    private var status = 200
    private var replyHeaders = mapOf<String, String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            requests += Request(exchange.requestURI.path, exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }, body)
            val reply = when {
                status != 200 -> """{"error":{"message":"slow down"}}"""
                body.has("input") -> """{"data":[{"embedding":[3.0,4.0]}]}"""
                else -> """{"embedding":{"values":[0.0,2.0]}}"""
            }
            replyHeaders.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
    private val asked = mutableListOf<EgressPayload>()

    @After
    fun tearDown() = server.stop(0)

    private fun session(protocol: RemoteProtocol, granted: Boolean = true) =
        RemoteEmbeddingSession(protocol, baseUrl, "sk-test-secret-key", "embed-x", {
            asked += it
            if (granted) EgressDecision.Allowed else EgressDecision.Denied(setOf(it), emptySet())
        })

    @Test
    fun `OpenAI Embeddings sends the sentence and reads a normalized vector`() {
        assertContentEquals(floatArrayOf(0.6f, 0.8f), session(RemoteProtocol.OPENAI_EMBEDDINGS).embedText("a pier at dusk"))
        val request = requests.single()
        assertEquals("/v1/embeddings", request.path)
        assertEquals("Bearer sk-test-secret-key", request.headers["authorization"])
        assertEquals("embed-x", request.body.getString("model"))
        assertEquals("a pier at dusk", request.body.getString("input"))
        assertEquals(listOf(EgressPayload.QUERY_TEXT), asked)
    }

    @Test
    fun `Gemini embedContent sends a sentence or an inline image to the model's path`() {
        val gemini = session(RemoteProtocol.GEMINI_EMBED_CONTENT)
        assertContentEquals(floatArrayOf(0f, 1f), gemini.embedText("a pier at dusk"))
        assertContentEquals(floatArrayOf(0f, 1f), gemini.embedImage(jpeg))
        val (text, image) = requests
        assertEquals("/v1/models/embed-x:embedContent", text.path)
        assertEquals("sk-test-secret-key", text.headers["x-goog-api-key"])
        assertEquals("a pier at dusk", text.body.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"))
        val inline = image.body.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getJSONObject("inline_data")
        assertEquals("image/jpeg", inline.getString("mime_type"))
        assertEquals(listOf(EgressPayload.QUERY_TEXT, EgressPayload.IMAGE_PIXELS), asked)
    }

    @Test
    fun `nothing reaches the server when the grants are off`() {
        for (protocol in listOf(RemoteProtocol.OPENAI_EMBEDDINGS, RemoteProtocol.GEMINI_EMBED_CONTENT)) {
            assertFailsWith<EgressDeniedException> { session(protocol, granted = false).embedText("a pier at dusk") }
        }
        assertFailsWith<EgressDeniedException> { session(RemoteProtocol.GEMINI_EMBED_CONTENT, granted = false).embedImage(jpeg) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a rate limit says when to try again and never shows the key`() {
        status = 429
        replyHeaders = mapOf("Retry-After" to "30")
        val failure = assertFailsWith<RemoteCallException> { session(RemoteProtocol.GEMINI_EMBED_CONTENT).embedText("a pier") }
        assertEquals(RemoteFailure.RateLimited(30), failure.failure)
        assertTrue("sk-test-secret-key" !in failure.toString())
    }

    @Test
    fun `the connection test finds images only where the format takes them`() = runTest {
        fun connection(protocol: RemoteProtocol) = RemoteConnection("c", protocol, baseUrl)
        val text = setOf(ModelCapability.INPUT_TEXT, ModelCapability.OUTPUT_EMBEDDING)
        assertEquals(text, RemoteCapabilityProbe().probe(connection(RemoteProtocol.OPENAI_EMBEDDINGS), "k", "embed-x"))
        assertEquals(text + ModelCapability.INPUT_IMAGE, RemoteCapabilityProbe().probe(connection(RemoteProtocol.GEMINI_EMBED_CONTENT), "k", "embed-x"))
    }
}
