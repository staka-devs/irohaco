package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.ModelNotInstalledException
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.TextIndexStore
import dev.irohaco.core.contracts.TextMatch
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.VectorHit
import dev.irohaco.core.contracts.VectorSearchStore
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Meaning and text are fused by rank, videos match by scene, and text still works without the model (M2-09). */
class SearchMediaTest {
    private val space = EmbeddingSpace("a", "Model", SpaceState.BUILDING, "sig", null, isDefault = true)
    private var vectorHits = listOf(VectorHit("p1", null), VectorHit("v1", 20_000), VectorHit("p2", null))
    private var textHits = listOf(TextMatch("p2", null, "看板"), TextMatch("v1", 40_000, "駅"))
    private var modelMissing = false

    private val vectors = object : VectorSearchStore {
        override fun spaces() = flowOf(listOf(space))
        override suspend fun space(spaceId: String) = space
        override suspend fun setDefault(spaceId: String) = false
        override suspend fun nearest(spaceId: String, query: FloatArray, limit: Int) = vectorHits
        override suspend fun coverage(spaceId: String) = 0.4f
    }
    private val texts = object : TextIndexStore {
        override suspend fun recordOcr(targetId: String, revisionDigest: String, text: String, engineRevision: String) {}
        override suspend fun search(query: String, limit: Int) = textHits
    }
    private val catalog = listOf(
        TimelineItem("p1", MediaKind.IMAGE, "content://p1", 0),
        TimelineItem("p2", MediaKind.IMAGE, "content://p2", 0),
        TimelineItem("v1", MediaKind.VIDEO, "content://v1", 0),
    )
    private val search = SearchMedia(
        SearchByMeaning(vectors, embedText = { if (modelMissing) throw ModelNotInstalledException() else floatArrayOf(1f) }, localSignature = "sig"),
        SearchText(texts),
        { ids -> catalog.filter { it.assetId in ids } },
    )

    @Test
    fun `meaning and text are fused by rank, and a file found by both comes first`() = runTest {
        val results = search("駅前の看板")
        assertEquals(listOf("p2" to null, "p1" to null, "v1" to 20_000L, "v1" to 40_000L), results.hits.map { it.item.assetId to it.timestampMs })
        assertEquals(setOf(Evidence.MEANING, Evidence.TEXT), results.hits.first().evidence)
        assertEquals("看板", results.hits.first().text)
        assertEquals(0.4f, results.coverage)
    }

    @Test
    fun `chips narrow the sources and the kinds`() = runTest {
        assertEquals(listOf("v1", "v1"), search("駅", kind = MediaKind.VIDEO).hits.map { it.item.assetId })
        assertEquals(setOf(Evidence.TEXT), search("駅", byMeaning = false).hits.flatMap { it.evidence }.toSet())
        assertNull(search("駅", byMeaning = false).space)
    }

    @Test
    fun `without the model the text search still answers`() = runTest {
        modelMissing = true
        val results = search("駅")
        assertEquals(true, results.meaningUnavailable)
        assertEquals(listOf("p2", "v1"), results.hits.map { it.item.assetId })
    }

    @Test
    fun `a file that is no longer available is left out`() = runTest {
        vectorHits = listOf(VectorHit("gone", null))
        textHits = emptyList()
        assertEquals(emptyList(), search("駅").hits)
    }
}
