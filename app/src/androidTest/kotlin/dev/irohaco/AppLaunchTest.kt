package dev.irohaco

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L2 smoke test: the real app starts on an emulator and its navigation works. */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun launchesOnPhotosAndSwitchesTabs() {
        rule.onNode(hasText(text(R.string.destination_photos)) and isSelected()).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.destination_library)).performClick()
        rule.onNode(hasText(text(R.string.destination_library)) and isSelected()).assertIsDisplayed()
    }
}
