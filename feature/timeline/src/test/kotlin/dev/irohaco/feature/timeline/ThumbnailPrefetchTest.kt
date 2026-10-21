package dev.irohaco.feature.timeline

import org.junit.Test
import kotlin.test.assertEquals

/** Which thumbnails load ahead of the scroll (M1-25). */
class ThumbnailPrefetchTest {
    @Test
    fun `scrolling down loads the next screenful`() {
        assertEquals(30..39, prefetchRange(first = 20, previous = 10, firstVisible = 20, lastVisible = 29))
    }

    @Test
    fun `scrolling up loads the screenful above, stopping at the top`() {
        assertEquals(10..<20, prefetchRange(first = 20, previous = 30, firstVisible = 20, lastVisible = 29))
        assertEquals(0..<5, prefetchRange(first = 5, previous = 8, firstVisible = 5, lastVisible = 14))
    }

    @Test
    fun `a fling moves more than a third of the visible cells in one sample`() {
        assertEquals(false, isFast(moved = 3, visible = 30))
        assertEquals(true, isFast(moved = 11, visible = 30))
        assertEquals(true, isFast(moved = -11, visible = 30)) // upwards too
    }

    @Test
    fun `loads nothing after a jump of several screens`() {
        assertEquals(IntRange.EMPTY, prefetchRange(first = 5_000, previous = 20, firstVisible = 5_000, lastVisible = 5_009))
    }
}
