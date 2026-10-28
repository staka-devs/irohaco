package dev.irohaco.feature.timeline

import org.junit.Test
import kotlin.test.assertEquals

/** Pinching walks the column steps of the width class (DESIGN §4.1). */
class ColumnStepsTest {
    private val levels = listOf(3, 4, 5, 7, 10)

    @Test
    fun `a step moves to the next column count`() {
        assertEquals(5, stepColumns(levels, 4, 1))
        assertEquals(3, stepColumns(levels, 4, -1))
    }

    @Test
    fun `the ends hold`() {
        assertEquals(10, stepColumns(levels, 10, 1))
        assertEquals(3, stepColumns(levels, 3, -1))
    }

    @Test
    fun `a count between steps, as after rotating, joins the step above it`() {
        assertEquals(7, stepColumns(levels, 6, 0))
        assertEquals(10, stepColumns(levels, 16, 0))
    }
}
