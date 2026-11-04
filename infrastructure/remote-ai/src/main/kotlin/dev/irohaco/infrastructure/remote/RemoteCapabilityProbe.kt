package dev.irohaco.infrastructure.remote

import android.graphics.Bitmap
import android.graphics.Color
import dev.irohaco.core.contracts.CapabilityProbe
import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.ModelCapability
import dev.irohaco.core.contracts.ProbedModel
import dev.irohaco.core.contracts.RemoteCallException
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.RemoteFailure
import dev.irohaco.core.contracts.RemoteProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The connection test (ADR-0032): a sample sentence, then the sample image, then the sentence streamed.
 * Only what answers is kept; a model that cannot even answer the sentence fails the test. Nothing of the
 * user's goes out, so it needs no grant, only the user's tap that starts it.
 */
class RemoteCapabilityProbe(private val timeoutMs: Int = 30_000) : CapabilityProbe {
    override suspend fun probe(connection: RemoteConnection, apiKey: String, modelId: String): ProbedModel {
        if (connection.protocol in EMBEDDING) return embeddings(connection, apiKey, modelId)
        fun session(streaming: Boolean) =
            RemoteAnswerSession(connection.protocol, connection.baseUrl, apiKey, modelId, streaming, { EgressDecision.Allowed }, timeoutMs)
        val found = mutableSetOf(ModelCapability.INPUT_TEXT, ModelCapability.OUTPUT_TEXT)
        check(session(streaming = false).answer(INSTRUCTION, emptyList(), TEXT).isNotBlank()) { "The model sent back no text" }
        if (works { session(streaming = false).answer(INSTRUCTION, listOf(sampleImage()), IMAGE).isNotBlank() }) found += ModelCapability.INPUT_IMAGE
        if (works { session(streaming = true).answer(INSTRUCTION, emptyList(), TEXT).isNotBlank() }) found += ModelCapability.STREAMING
        return ProbedModel(found)
    }

    /** The sample sentence, whose vector gives the length, then the sample image where the format can carry one. */
    private suspend fun embeddings(connection: RemoteConnection, apiKey: String, modelId: String): ProbedModel = withContext(Dispatchers.IO) {
        val session = RemoteEmbeddingSession(connection.protocol, connection.baseUrl, apiKey, modelId, { EgressDecision.Allowed }, timeoutMs)
        val dimension = session.embedText(TEXT).size
        val found = mutableSetOf(ModelCapability.INPUT_TEXT, ModelCapability.OUTPUT_EMBEDDING)
        if (connection.protocol == RemoteProtocol.GEMINI_EMBED_CONTENT && works { session.embedImage(sampleImage()).isNotEmpty() }) found += ModelCapability.INPUT_IMAGE
        ProbedModel(found, dimension)
    }

    /** A refused image or stream only leaves that capability out; a rate limit or a cancel is still thrown. */
    private suspend fun works(call: suspend () -> Boolean): Boolean = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteCallException) {
        if (e.failure is RemoteFailure.Failed) false else throw e
    }

    private companion object {
        val EMBEDDING = setOf(RemoteProtocol.OPENAI_EMBEDDINGS, RemoteProtocol.GEMINI_EMBED_CONTENT)
        const val INSTRUCTION = "Answer in one short line."
        const val TEXT = "Reply with the word OK."
        const val IMAGE = "What color is the left half of this image?"

        /** Red on the left, blue on the right: the app's own sample, not one of the user's photos. */
        fun sampleImage(): ByteArray {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            for (x in 0 until 64) for (y in 0 until 64) bitmap.setPixel(x, y, if (x < 32) Color.RED else Color.BLUE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
    }
}
