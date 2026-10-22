package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.IndexImageReader
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexItemSkipped
import dev.irohaco.core.contracts.IndexSource
import dev.irohaco.core.contracts.MediaIndexStore
import dev.irohaco.core.contracts.TextIndexStore
import dev.irohaco.core.contracts.TextMatch
import dev.irohaco.core.contracts.TextRecognizer
import dev.irohaco.core.contracts.VideoQuality
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Images and video frames are embedded only at the revision they were planned at (M2-05, M2-06). */
class IndexMediaTest {
    private val sources = mutableMapOf<String, IndexSource>(
        "t1" to IndexSource.Image("content://1", "r1"),
        "f1" to IndexSource.Frame("content://v", 15_000, "r1"),
    )
    private val read = mutableListOf<String>()

    private val store = object : MediaIndexStore {
        override suspend fun ensureSpace(newSpaceId: String, spec: EmbeddingSpaceSpec, signature: String) = "s"
        override suspend fun hasVectors(spaceId: String) = false
        override suspend fun plan(spaceId: String, folders: Set<String>?, quality: VideoQuality, now: Long) = emptyList<IndexItem>()
        override suspend fun source(targetId: String) = sources[targetId]
        override fun lowCoverageVideos() = flowOf(0)
    }

    private val reader = object : IndexImageReader {
        override suspend fun read(contentUri: String) = byteArrayOf(1).also { read += contentUri }
        override suspend fun readFrame(contentUri: String, timestampMs: Long) = byteArrayOf(2).also { read += "$contentUri@$timestampMs" }
    }

    private var onEmbed: () -> Unit = {}
    private var ocr: suspend (ByteArray) -> String = { "看板" }
    private val recorded = mutableListOf<String>()
    private val recognizer = object : TextRecognizer {
        override val engineRevision = "e"
        override suspend fun recognize(image: ByteArray) = ocr(image)
    }
    private val texts = object : TextIndexStore {
        override suspend fun recordOcr(targetId: String, revisionDigest: String, text: String, engineRevision: String) {
            recorded += "$targetId@$revisionDigest=$text"
        }
        override suspend fun search(query: String, limit: Int) = emptyList<TextMatch>()
    }
    private val embedMedia = EmbedMediaItem(store, reader, recognizer, texts) { onEmbed(); floatArrayOf(it[0].toFloat()) }

    @Test
    fun `an image at its planned revision is read and embedded`() = runTest {
        assertContentEquals(floatArrayOf(1f), embedMedia.embed(IndexItem("t1", "r1")))
        assertEquals(listOf("content://1"), read)
    }

    @Test
    fun `a video frame is read at its time`() = runTest {
        assertContentEquals(floatArrayOf(2f), embedMedia.embed(IndexItem("f1", "r1")))
        assertEquals(listOf("content://v@15000"), read)
    }

    @Test
    fun `the text read from the image is kept with its revision`() = runTest {
        embedMedia.embed(IndexItem("t1", "r1"))
        assertEquals(listOf("t1@r1=看板"), recorded)
    }

    @Test
    fun `text that cannot be read leaves the vector in place`() = runTest {
        ocr = { error("recognizer failed") }
        assertContentEquals(floatArrayOf(1f), embedMedia.embed(IndexItem("t1", "r1")))
        assertEquals(emptyList(), recorded)
    }

    @Test
    fun `no text is kept for an image changed while it was read`() = runTest {
        onEmbed = { sources["t1"] = IndexSource.Image("content://1", "r2") }
        assertFailsWith<IndexItemSkipped> { embedMedia.embed(IndexItem("t1", "r1")) }
        assertEquals(emptyList(), recorded)
    }

    @Test
    fun `a gone or changed image is skipped without reading it`() = runTest {
        assertFailsWith<IndexItemSkipped> { embedMedia.embed(IndexItem("t2", "r1")) }
        assertFailsWith<IndexItemSkipped> { embedMedia.embed(IndexItem("t1", "r0")) }
        assertEquals(emptyList(), read)
    }

    @Test
    fun `an image changed while the model ran is skipped`() = runTest {
        onEmbed = { sources["t1"] = IndexSource.Image("content://1", "r2") }
        assertFailsWith<IndexItemSkipped> { embedMedia.embed(IndexItem("t1", "r1")) }
    }

    @Test
    fun `another model file or preprocessing gives another space`() {
        val spec = ImageSpaces.LOCAL
        assertEquals(ImageSpaces.signature(spec), ImageSpaces.signature(spec.copy()))
        assertNotEquals(ImageSpaces.signature(spec), ImageSpaces.signature(spec.copy(modelDigest = "other")))
        assertNotEquals(ImageSpaces.signature(spec), ImageSpaces.signature(spec.copy(preprocessingSignature = "other")))
    }
}
