package dev.irohaco.core.designsystem.motion

import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Turning animations off in the system's accessibility settings reaches every screen that asks (DESIGN §7, #250). */
@RunWith(RobolectricTestRunner::class)
class ReducedMotionTest {
    private val resolver = RuntimeEnvironment.getApplication().contentResolver

    @Test
    fun `animations play when the system leaves them on`() {
        assertFalse(isReducedMotion(resolver))
    }

    @Test
    fun `animations stop when the system turns them off`() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(isReducedMotion(resolver))
    }
}
