package dev.irohaco.feature.timeline.viewer

import android.content.Intent
import dev.irohaco.core.domain.GeoPoint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The Intent handed to the device's map app (PRD GAL-013). */
@RunWith(RobolectricTestRunner::class)
class MediaIntentsTest {
    @Test
    fun `opens the device's map app at the photo's coordinates`() {
        val intent = mapIntent(GeoPoint(35.6895, 139.6917))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo:35.6895,139.6917?q=35.6895,139.6917", intent.data.toString())
    }
}
