package dev.irohaco.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IrohacoAppBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `back from another tab returns to photos, and back on photos leaves the app`() {
        rule.setContent { IrohacoTheme(dynamicColor = false) { IrohacoApp() } }

        rule.onNodeWithText("Search").performClick()
        rule.onNode(hasText("Search") and isSelected()).assertExists()

        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNode(hasText("Photos") and isSelected()).assertExists()

        rule.activityRule.scenario.onActivity {
            assertFalse(it.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }
}
