package dev.irohaco.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The time left on the indexing screen comes from the rate seen so far (#179). */
class MinutesLeftTest {
    @Test
    fun `no estimate until the rate settles`() {
        assertNull(minutesLeft(0, 0, 5, 60_000, 1_000))
        assertNull(minutesLeft(0, 0, 100, 10_000, 1_000))
    }

    @Test
    fun `the items left at the rate so far, rounded up to a minute`() {
        // 75 items a minute, as measured on a device in #24.
        assertEquals(170L, minutesLeft(100, 0, 175, 60_000, 12_700))
        assertEquals(1L, minutesLeft(0, 0, 75, 60_000, 1))
    }
}
