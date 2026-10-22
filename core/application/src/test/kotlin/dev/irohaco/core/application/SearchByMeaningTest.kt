package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.VectorHit
import dev.irohaco.core.contracts.VectorSearchStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A search reads one space only, the default unless one is picked, and never one of another model file (M2-08). */
class SearchByMeaningTest {
    private val a = EmbeddingSpace("a", "Model", SpaceState.READY, "sig", "q: ", isDefault = true)
    private val b = EmbeddingSpace("b", "Model", SpaceState.BUILDING, "sig", "q: ", isDefault = false)
    private val old = EmbeddingSpace("old", "Model", SpaceState.STALE, "old-sig", "q: ", isDefault = false)
    private val spaces = MutableStateFlow(listOf(a, b, old))
    private val searched = mutableListOf<String>()
    private val embedded = mutableListOf<String>()

    private val store = object : VectorSearchStore {
        override fun spaces() = spaces
        override suspend fun space(spaceId: String) = spaces.value.firstOrNull { it.spaceId == spaceId }
        override suspend fun setDefault(spaceId: String) = false
        override suspend fun nearest(spaceId: String, query: FloatArray, limit: Int) = listOf(VectorHit("x", null)).also { searched += spaceId }
        override suspend fun coverage(spaceId: String) = if (spaceId == "b") 0.4f else 1f
    }

    private val search = SearchByMeaning(store, { embedded += it; floatArrayOf(1f) }, localSignature = "sig")

    @Test
    fun `searches the default space with the query prefix`() = runTest {
        val result = search("夕焼けの海")
        assertEquals("a", result?.space?.spaceId)
        assertEquals(listOf("q: 夕焼けの海"), embedded)
        assertEquals(listOf("a"), searched)
    }

    @Test
    fun `a space being built can be picked and shows its coverage`() = runTest {
        val result = search("海", spaceId = "b")
        assertEquals(0.4f, result?.coverage)
        assertEquals(listOf("b"), searched)
    }

    @Test
    fun `a space of another model file or no space at all is not searched`() = runTest {
        assertNull(search("海", spaceId = "old"))
        spaces.value = emptyList()
        assertNull(search("海"))
        assertNull(search(" "))
        assertEquals(emptyList(), searched)
    }
}
