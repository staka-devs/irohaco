package dev.irohaco.feature.timeline

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals

/** A video's length in the grid's corner and in what TalkBack reads (M1-26). */
@RunWith(RobolectricTestRunner::class)
class ThumbnailTextTest {
    @Test
    fun `shows the length the way players do`() {
        assertEquals("0:46", formatDuration(46_900))
        assertEquals("12:05", formatDuration(725_000))
        assertEquals("1:01:19", formatDuration(3_679_000))
    }

    @Test
    fun `reads the length in words`() {
        assertEquals("1 minute, 1 second", spokenDuration(61_000, Locale.US))
        assertEquals("0 seconds", spokenDuration(400, Locale.US))
    }
}
