package dev.irohaco.feature.timeline.search

import android.os.Looper
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.test.assertEquals

/** The search bar stays in place on this screen: typing and searching run the search, and the field is still there. */
@RunWith(RobolectricTestRunner::class)
class SearchBarTest {
    private val rule = createComposeRule()

    // Typing leaves frames queued on the shared AndroidUiDispatcher when the v2 rule ends. Robolectric drops them
    // between tests, so the dispatcher would wait for good and the next test's paged items would never arrive.
    @get:Rule val chain: RuleChain = RuleChain.outerRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                base.evaluate()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            }
        }
    }.around(rule)

    private val searched = mutableListOf<String>()

    private fun show() = rule.setContent {
        IrohacoTheme(dynamicColor = false) {
            SearchContent(SearchUi(), emptyList(), { searched += it }, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test
    fun `tapping the bar puts the cursor in it, so the keyboard comes up`() {
        show()
        rule.onNode(hasSetTextAction()).performClick()
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `searching from the bar runs the search and keeps the bar`() {
        show()
        rule.onNode(hasSetTextAction()).performTextInput("station")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("station"), searched)
        rule.onNode(hasSetTextAction()).assertExists()
    }
}
