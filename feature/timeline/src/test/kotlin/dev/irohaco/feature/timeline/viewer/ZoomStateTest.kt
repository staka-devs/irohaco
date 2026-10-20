package dev.irohaco.feature.timeline.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomStateTest {
    private val size = Size(1000f, 2000f)

    @Test
    fun `the point under the fingers stays put while zooming`() {
        val zoom = ZoomState()
        val centroid = Offset(200f, -300f)
        val before = (centroid - zoom.offset) / zoom.scale // content point under the fingers
        zoom.transform(2f, Offset.Zero, centroid, size)
        assertEquals(before, (centroid - zoom.offset) / zoom.scale)
        assertEquals(Offset(-200f, 300f), zoom.offset)
    }

    @Test
    fun `panning stops at the edge of the zoomed photo`() {
        val zoom = ZoomState()
        zoom.transform(2f, Offset(10_000f, -10_000f), Offset.Zero, size)
        assertEquals(Offset(500f, -1000f), zoom.offset) // (2 - 1) * size / 2
    }

    @Test
    fun `scale stays between fit and the maximum, and fit means no offset`() {
        val zoom = ZoomState()
        zoom.transform(0.5f, Offset(300f, 0f), Offset.Zero, size)
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
        zoom.transform(100f, Offset.Zero, Offset.Zero, size)
        assertEquals(ZoomState.MAX_SCALE, zoom.scale)
    }

    @Test
    fun `double tap zooms in and back out`() {
        val zoom = ZoomState()
        zoom.toggle(Offset(100f, 100f), size)
        assertTrue(zoom.isZoomed)
        assertEquals(ZoomState.DOUBLE_TAP_SCALE, zoom.scale)
        zoom.toggle(Offset.Zero, size)
        assertFalse(zoom.isZoomed)
        assertEquals(Offset.Zero, zoom.offset)
    }
}
