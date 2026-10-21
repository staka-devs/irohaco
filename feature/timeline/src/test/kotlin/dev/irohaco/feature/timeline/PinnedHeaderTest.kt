package dev.irohaco.feature.timeline

import org.junit.Test
import kotlin.test.assertEquals

/** The pinned date header gives way to the next one (M1-31). */
class PinnedHeaderTest {
    @Test
    fun `stays put while the next header is further down`() {
        assertEquals(0, pinnedHeaderOffset(nextHeaderTop = 500, pinnedHeight = 120))
        assertEquals(0, pinnedHeaderOffset(nextHeaderTop = null, pinnedHeight = 120))
    }

    @Test
    fun `moves up by as much as the next header overlaps it`() {
        assertEquals(-40, pinnedHeaderOffset(nextHeaderTop = 80, pinnedHeight = 120))
        assertEquals(-120, pinnedHeaderOffset(nextHeaderTop = 0, pinnedHeight = 120))
    }
}
