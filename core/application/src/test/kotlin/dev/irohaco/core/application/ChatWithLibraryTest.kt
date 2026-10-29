package dev.irohaco.core.application

import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatWithLibraryTest {
    private fun hit(id: String, at: Long? = null) = SearchHit(TimelineItem(id, MediaKind.IMAGE, "content://$id", 0), at, setOf(Evidence.MEANING), null)

    @Test
    fun `a follow-up keeps what the last answer cited first, then the new search, without repeats`() {
        val last = AskResult.Answered("A pier [1].", known = true, citations = listOf(Citation(hit("a"), Basis.OBSERVED, 1), Citation(hit("b"), Basis.OBSERVED, 2), Citation(hit("c"), Basis.OBSERVED, 3)))
        val sources = ChatWithLibrary.sources(listOf(hit("b"), hit("d"), hit("e"), hit("f")), listOf(ChatWithLibrary.Turn("Where?", last)))
        assertEquals(listOf("a", "b", "d", "e"), sources.map { it.item.assetId })
    }

    @Test
    fun `a first question is answered from the search alone`() {
        assertEquals(listOf("x", "y"), ChatWithLibrary.sources(listOf(hit("x"), hit("y")), emptyList()).map { it.item.assetId })
    }
}
