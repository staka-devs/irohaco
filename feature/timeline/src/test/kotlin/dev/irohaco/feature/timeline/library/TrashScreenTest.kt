package dev.irohaco.feature.timeline.library

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The trash row's "days left" text (PRD GAL-015). */
class TrashScreenTest {
    @Test
    fun `counts whole days left, clamped at zero, and unknown when there is no expiry`() {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        assertEquals(5, daysRemaining(now + 5 * day + 1_000))
        assertEquals(0, daysRemaining(now - day)) // already past: clamped, not negative
        assertNull(daysRemaining(null))
    }
}
