package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.ImageIndexStore
import dev.irohaco.core.contracts.IndexImageSource
import dev.irohaco.core.contracts.IndexItem
import dev.irohaco.core.contracts.IndexItemSkipped
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Images are embedded only at the revision they were planned at (M2-05, INDEX_STATE_MACHINE.md §4). */
class IndexImagesTest {
    private val sources = mutableMapOf("t1" to IndexImageSource("content://1", "r1"))
    private val read = mutableListOf<String>()

    private val store = object : ImageIndexStore {
        override suspend fun ensureSpace(newSpaceId: String, spec: EmbeddingSpaceSpec, signature: String) = "s"
        override suspend fun hasVectors(spaceId: String) = false
        override suspend fun planImages(spaceId: String, folders: Set<String>?) = emptyList<IndexItem>()
        override suspend fun imageSource(targetId: String) = sources[targetId]
    }

    private var onEmbed: () -> Unit = {}
    private val embedImage = EmbedImageItem(store, { read += it; byteArrayOf(1) }) { onEmbed(); floatArrayOf(0.5f) }

    @Test
    fun `an image at its planned revision is read and embedded`() = runTest {
        assertContentEquals(floatArrayOf(0.5f), embedImage.embed(IndexItem("t1", "r1")))
        assertEquals(listOf("content://1"), read)
    }

    @Test
    fun `a gone or changed image is skipped without reading it`() = runTest {
        assertFailsWith<IndexItemSkipped> { embedImage.embed(IndexItem("t2", "r1")) }
        assertFailsWith<IndexItemSkipped> { embedImage.embed(IndexItem("t1", "r0")) }
        assertEquals(emptyList(), read)
    }

    @Test
    fun `an image changed while the model ran is skipped`() = runTest {
        onEmbed = { sources["t1"] = IndexImageSource("content://1", "r2") }
        assertFailsWith<IndexItemSkipped> { embedImage.embed(IndexItem("t1", "r1")) }
    }

    @Test
    fun `another model file or preprocessing gives another space`() {
        val spec = ImageSpaces.LOCAL
        assertEquals(ImageSpaces.signature(spec), ImageSpaces.signature(spec.copy()))
        assertNotEquals(ImageSpaces.signature(spec), ImageSpaces.signature(spec.copy(modelDigest = "other")))
        assertNotEquals(ImageSpaces.signature(spec), ImageSpaces.signature(spec.copy(preprocessingSignature = "other")))
    }
}
