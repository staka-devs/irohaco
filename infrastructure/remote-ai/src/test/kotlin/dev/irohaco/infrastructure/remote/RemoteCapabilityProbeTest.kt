package dev.irohaco.infrastructure.remote

import com.sun.net.httpserver.HttpServer
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The connection test keeps only what the model answered (ADR-0032). */
@RunWith(RobolectricTestRunner::class)
class RemoteCapabilityProbeTest {
    private var imageStatus = 200
    private var streams = true
    private var textStatus = 200
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            val hasImage = "image_url" in body.toString()
            val stream = body.getBoolean("stream")
            val (status, reply) = when {
                textStatus != 200 -> textStatus to "{}"
                hasImage -> imageStatus to """{"choices":[{"message":{"content":"Red"}}]}"""
                stream && streams -> 200 to "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n"
                stream -> 400 to """{"error":{"message":"stream is not supported"}}"""
                else -> 200 to """{"choices":[{"message":{"content":"OK"}}]}"""
            }
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val connection = RemoteConnection("c", RemoteProtocol.OPENAI_CHAT, "http://127.0.0.1:${server.address.port}/v1")

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun `a model that reads images and streams has both kept`() = runTest {
        assertEquals(ModelCapability.entries.toSet() - ModelCapability.INPUT_VIDEO - ModelCapability.OUTPUT_EMBEDDING, RemoteCapabilityProbe().probe(connection, "k", "m").capabilities)
    }

    @Test
    fun `what the model refuses is left out`() = runTest {
        imageStatus = 400
        streams = false
        assertEquals(setOf(ModelCapability.INPUT_TEXT, ModelCapability.OUTPUT_TEXT), RemoteCapabilityProbe().probe(connection, "k", "m").capabilities)
    }

    @Test
    fun `a refused key fails the test`() = runTest {
        textStatus = 401
        assertEquals(RemoteFailure.Unauthorized, assertFailsWith<RemoteCallException> { RemoteCapabilityProbe().probe(connection, "k", "m") }.failure)
    }
}
