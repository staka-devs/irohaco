package dev.irohaco.feature.timeline.viewer

import org.junit.Test
import kotlin.test.assertEquals

class DetailFormatTest {
    @Test
    fun `shutter speeds read like a camera's`() {
        assertEquals("1/250 s", shutter(0.004))
        assertEquals("1/2 s", shutter(0.5))
        assertEquals("2.5 s", shutter(2.5))
    }

    @Test
    fun `durations switch to hours past one hour`() {
        assertEquals("0:05", duration(5_400))
        assertEquals("12:03", duration(723_000))
        assertEquals("1:00:07", duration(3_607_000))
    }
}
