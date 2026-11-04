package dev.irohaco.core.designsystem.motion

import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Turning animations off in the system's accessibility settings reaches every screen that asks (DESIGN §7, #250). */
@RunWith(RobolectricTestRunner::class)
class ReducedMotionTest {
    @get:Rule val rule = createComposeRule()

    private fun reducedMotion(): Boolean {
        var reduced: Boolean? = null
        rule.setContent { reduced = rememberReducedMotion() }
        rule.waitForIdle()
        return reduced!!
    }

    @Test
    fun `animations play when the system leaves them on`() {
        assertFalse(reducedMotion())
    }

    @Test
    fun `animations stop when the system turns them off`() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(reducedMotion())
    }
}
