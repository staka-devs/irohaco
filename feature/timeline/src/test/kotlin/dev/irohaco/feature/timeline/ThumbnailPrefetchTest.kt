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
}
