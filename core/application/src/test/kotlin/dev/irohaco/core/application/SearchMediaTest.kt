package dev.irohaco.core.application

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

/** Meaning and text are fused by rank, videos match by scene, and text still works without the model (M2-09). */
class SearchMediaTest {
    private val space = testSpace("a", SpaceState.BUILDING, isDefault = true, prefix = null)
    private val other = testSpace("b", SpaceState.READY, prefix = null, preprocessing = "p2")
    private val otherHits = listOf(VectorHit("p2", null, 0.8f), VectorHit("p1", null, 0.7f))
    private var vectorHits = listOf(VectorHit("p1", null, 0.5f), VectorHit("v1", 20_000, 0.4f), VectorHit("p2", null, 0.3f))
    private var textHits = listOf(TextMatch("p2", null, "看板"), TextMatch("v1", 40_000, "駅"))
    private var modelMissing = false
    private var embeds = 0
    private var revoked = emptySet<String>()
    private val tagged = mapOf("trip" to setOf("p2", "v1"))

    private val vectors = object : VectorSearchStore {
        override fun spaces() = flowOf(listOf(space, other))
        override suspend fun space(spaceId: String) = if (spaceId == "b") other else space
        override suspend fun setDefault(spaceId: String) = false
        override suspend fun markStale(spaceIds: List<String>) {}
        override suspend fun nearest(spaceId: String, query: FloatArray, limit: Int) = if (spaceId == "b") otherHits else vectorHits
        override suspend fun coverage(spaceId: String) = 0.4f
        override suspend fun vectorOf(spaceId: String, assetId: String) = if (assetId == "p1") floatArrayOf(1f) else null
    }
    private val texts = object : TextIndexStore {
        override suspend fun recordOcr(targetId: String, revisionDigest: String, text: String, engineRevision: String) {}
        override suspend fun search(query: String, limit: Int) = textHits
    }
    private val catalog = listOf(
        TimelineItem("p1", MediaKind.IMAGE, "content://p1", 1_000),
        TimelineItem("p2", MediaKind.IMAGE, "content://p2", 0),
        TimelineItem("v1", MediaKind.VIDEO, "content://v1", 0),
    )
    private val search = SearchMedia(
        SearchByMeaning(vectors, embedText = { embeds++; if (modelMissing) throw ModelNotInstalledException() else floatArrayOf(1f) }, modelDigest = "d"),
        SearchText(texts),
        { ids, tag -> catalog.filter { it.assetId in ids && (tag == null || it.assetId in tagged.getValue(tag)) } },
        { items -> items.filter { it.assetId !in revoked } },
    )

    @Test
    fun `a file the OS no longer lets the app read is left out, though the catalog still lists it`() = runTest {
        revoked = setOf("p2")
        assertEquals(listOf("p1", "v1", "v1"), search("駅前の看板").hits.map { it.item.assetId })
        assertEquals(emptyList(), search.similar("p1").hits.filter { it.item.assetId == "p2" })
    }

    @Test
    fun `meaning and text are fused by rank, and a file found by both comes first`() = runTest {
        val results = search("駅前の看板")
        assertEquals(listOf("p2" to null, "p1" to null, "v1" to 20_000L, "v1" to 40_000L), results.hits.map { it.item.assetId to it.timestampMs })
        assertEquals(setOf(Evidence.MEANING, Evidence.TEXT), results.hits.first().evidence)
        assertEquals("看板", results.hits.first().text)
        assertEquals(listOf(0.4f), results.searched.map { it.coverage })
    }

    @Test
    fun `comparing searches every space, fuses them by rank and keeps each model's rank`() = runTest {
        val results = search("駅前の看板", compare = true)
        assertEquals(listOf("p2", "p1", "v1", "v1"), results.hits.map { it.item.assetId })
        assertEquals(mapOf("a" to 3, "b" to 1), results.hits[0].ranks)
        assertEquals(mapOf("a" to 1, "b" to 2), results.hits[1].ranks)
        assertEquals(mapOf("a" to 0.3f, "b" to 0.8f), results.hits[0].similarities)
        assertEquals(listOf("a", "b"), results.searched.map { it.space.spaceId })
        assertEquals(1, embeds)
    }

    @Test
    fun `a memo that matches says so, and the memo chip leaves memos out`() = runTest {
        vectorHits = listOf(VectorHit("p1", null, 0.5f, fromMemo = true))
        textHits = listOf(TextMatch("p2", null, "祖母の家", fromMemo = true), TextMatch("p1", null, "駅"))
        val results = search("祖母")
        assertEquals(setOf(Evidence.MEMO, Evidence.TEXT), results.hits.first { it.item.assetId == "p1" }.evidence)
        assertEquals(setOf(Evidence.MEMO), results.hits.first { it.item.assetId == "p2" }.evidence)
        assertEquals(listOf("p1"), search("祖母", byMemo = false).hits.map { it.item.assetId })
    }

    @Test
    fun `a date range and a tag narrow the results`() = runTest {
        assertEquals(listOf("p1"), search("駅", filters = SearchFilters(fromMs = 1_000, untilMs = 2_000)).hits.map { it.item.assetId })
        assertEquals(listOf("p2", "v1", "v1"), search("駅", filters = SearchFilters(tagId = "trip")).hits.map { it.item.assetId })
    }

    @Test
    fun `similar photos come from the photo's own vector, without the photo itself`() = runTest {
        val results = search.similar("p1")
        assertEquals(listOf("v1", "p2"), results.hits.map { it.item.assetId })
        assertEquals(setOf(Evidence.MEANING), results.hits.flatMap { it.evidence }.toSet())
        assertEquals(true, search.similar("p2").meaningUnavailable)
    }

    @Test
    fun `chips narrow the sources and the kinds`() = runTest {
        assertEquals(listOf("v1", "v1"), search("駅", kind = MediaKind.VIDEO).hits.map { it.item.assetId })
        assertEquals(setOf(Evidence.TEXT), search("駅", byMeaning = false).hits.flatMap { it.evidence }.toSet())
        assertEquals(emptyList(), search("駅", byMeaning = false).searched)
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
        vectorHits = listOf(VectorHit("gone", null, 0.5f))
        textHits = emptyList()
        assertEquals(emptyList(), search("駅").hits)
    }
}
