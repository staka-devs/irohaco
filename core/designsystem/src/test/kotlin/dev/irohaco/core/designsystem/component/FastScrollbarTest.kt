package dev.irohaco.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fast scrollbar's drag-position math (PRD GAL-014): covers the whole grid, loaded or not. */
class FastScrollbarTest {
    @Test
    fun `maps the thumb's drag position across the whole item range`() {
        assertEquals(0, indexFor(offsetPx = 0f, trackHeightPx = 200f, thumbHeightPx = 48f, itemCount = 1_000))
        assertEquals(999, indexFor(offsetPx = 152f, trackHeightPx = 200f, thumbHeightPx = 48f, itemCount = 1_000))
        assertEquals(500, indexFor(offsetPx = 76f, trackHeightPx = 200f, thumbHeightPx = 48f, itemCount = 1_000)) // the exact midpoint rounds up
        // Past either end of the track clamps instead of going out of range.
        assertEquals(0, indexFor(offsetPx = -50f, trackHeightPx = 200f, thumbHeightPx = 48f, itemCount = 1_000))
        assertEquals(999, indexFor(offsetPx = 500f, trackHeightPx = 200f, thumbHeightPx = 48f, itemCount = 1_000))
    }

    @Test
    fun `the thumb's own position is the inverse mapping, for following normal scrolling`() {
        assertEquals(0f, offsetFor(index = 0, itemCount = 1_000, trackHeightPx = 200f, thumbHeightPx = 48f))
        assertEquals(152f, offsetFor(index = 999, itemCount = 1_000, trackHeightPx = 200f, thumbHeightPx = 48f))
    }
}
