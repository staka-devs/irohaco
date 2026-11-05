package dev.irohaco.feature.timeline

import dev.irohaco.core.contracts.Tag
import org.junit.Test
import kotlin.test.assertEquals

/** Looking a tag up when adding one (GAL-007). */
class TagSearchTest {
    private val tags = listOf(
        Tag("1", "旅行", 3),
        Tag("2", "旅行 2025", 40),
        Tag("3", "家族旅行", 12),
        Tag("4", "Trip", 5),
        Tag("5", "ラーメン", 8),
    )

    private fun names(query: String) = matchingTags(tags, query).map { it.name }

    @Test
    fun `nothing typed lists every tag, the most used first`() {
        assertEquals(listOf("旅行 2025", "家族旅行", "ラーメン", "Trip", "旅行"), names(""))
    }

    @Test
    fun `the same name comes first, then names that start with it, then the rest`() {
        assertEquals(listOf("旅行", "旅行 2025", "家族旅行"), names("旅行"))
    }

    @Test
    fun `case, width and kana do not matter`() {
        assertEquals(listOf("Trip"), names("ｔｒｉｐ"))
        assertEquals(listOf("ラーメン"), names("らーめん"))
        assertEquals(listOf("ラーメン"), names("ﾗｰﾒﾝ"))
    }

    @Test
    fun `a name no tag has matches nothing`() {
        assertEquals(emptyList(), names("海"))
    }
}
