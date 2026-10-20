package dev.irohaco.feature.timeline

import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaKind
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class SelectionTest {
    private val day = LocalDate.of(2026, 10, 20)
    private fun item(id: String) = TimelineItem(id, MediaKind.IMAGE, "content://$id", 0)

    // A header, three photos, another header, two photos; index 7 is not loaded yet.
    private val cells: List<TimelineCell?> = listOf(
        TimelineCell.Header(day), media("a"), media("b"), media("c"), TimelineCell.Header(day.minusDays(1)), media("d"), media("e"), null,
    )
    private fun media(id: String) = TimelineCell.Media(item(id), day)

    @Test
    fun `a group's button selects all of it, and unselects it once all are selected`() {
        val group = listOf(item("a"), item("b"))
        val some = mapOf("a" to item("a"), "x" to item("x"))
        val all = some.toggledGroup(group)
        assertEquals(setOf("a", "b", "x"), all.keys)
        assertEquals(setOf("x"), all.toggledGroup(group).keys)
    }

    @Test
    fun `dragging selects the cells between the start and the finger, skipping headers`() {
        val selected = rangeSelection(emptyMap(), cells::getOrNull, from = 2, to = 6)
        assertEquals(listOf("b", "c", "d", "e"), selected.keys.toList())
    }

    @Test
    fun `dragging upwards works too and keeps what was selected before`() {
        val selected = rangeSelection(mapOf("x" to item("x")), cells::getOrNull, from = 3, to = 1)
        assertEquals(setOf("x", "a", "b", "c"), selected.keys)
    }

    @Test
    fun `unloaded cells are skipped`() {
        assertEquals(listOf("e"), rangeSelection(emptyMap(), cells::getOrNull, from = 6, to = 7).keys.toList())
    }

    @Test
    fun `toggling adds and removes`() {
        val once = emptyMap<String, TimelineItem>().toggled(item("a")).toggled(item("b"))
        assertEquals(listOf("a", "b"), once.keys.toList())
        assertEquals(listOf("b"), once.toggled(item("a")).keys.toList())
    }
}
