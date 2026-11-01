package dev.irohaco.feature.timeline.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Test
import kotlin.test.assertEquals

class ZoomDetailTest {
    private val image = IntSize(4000, 3000)
    private val container = Size(1000f, 2000f) // fit scale 0.25, the photo 750 high in the middle

    @Test
    fun `at fit the whole photo is on screen`() {
        assertEquals(Rect(0f, 0f, 4000f, 3000f), visibleRegion(image, container, 1f, Offset.Zero))
    }

    @Test
    fun `zoomed on the center, the middle of the photo is on screen and lands back on the screen`() {
        val region = visibleRegion(image, container, 4f, Offset.Zero)!!
        // At 4x one photo pixel is one screen pixel, so the screen's 1000 x 2000 shows that much of the photo.
        assertEquals(Rect(1500f, 500f, 2500f, 2500f), region)
        assertEquals(Rect(0f, 0f, 1000f, 2000f), screenRect(region, image, container, 4f, Offset.Zero))
    }

    @Test
    fun `panning moves the region the other way`() {
        val region = visibleRegion(image, container, 4f, Offset(1000f, 0f))!!
        assertEquals(500f, region.left)
        assertEquals(1500f, region.right)
    }

    @Test
    fun `the sample keeps at least one photo pixel per screen pixel`() {
        assertEquals(4, sampleSize(image, container, 1f)) // 4 photo pixels per screen pixel
        assertEquals(2, sampleSize(image, container, 1.5f)) // 2.67
        assertEquals(1, sampleSize(image, container, 3f)) // 1.33
        assertEquals(1, sampleSize(image, container, 5f))
    }

    @Test
    fun `a region of a turned photo is found in the file's own pixels`() {
        val raw = IntSize(3000, 4000) // a portrait file shown landscape after its EXIF turn
        val shown = Rect(100f, 200f, 400f, 600f)
        assertEquals(shown, rawRect(shown, raw, 0))
        assertEquals(Rect(200f, 3600f, 600f, 3900f), rawRect(shown, raw, 90))
        assertEquals(Rect(2600f, 3400f, 2900f, 3800f), rawRect(shown, raw, 180))
        assertEquals(Rect(2400f, 100f, 2800f, 400f), rawRect(shown, raw, 270))
    }
}
