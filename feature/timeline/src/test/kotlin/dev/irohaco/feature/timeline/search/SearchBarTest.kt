package dev.irohaco.feature.timeline.search

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The search bar stays in place on this screen: typing and searching run the search, and the field is still there. */
@RunWith(RobolectricTestRunner::class)
class SearchBarTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `searching from the bar runs the search and keeps the bar`() {
        val searched = mutableListOf<String>()
        rule.setContent {
            IrohacoTheme(dynamicColor = false) {
                SearchContent(SearchUi(), emptyList(), { searched += it }, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput("station")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("station"), searched)
        rule.onNode(hasSetTextAction()).assertExists()
    }
}
