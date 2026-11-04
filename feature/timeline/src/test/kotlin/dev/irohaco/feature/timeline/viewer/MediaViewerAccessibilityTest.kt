package dev.irohaco.feature.timeline.viewer

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** TalkBack and switch access reach the swipe and the pinch through actions (DESIGN §7, #251). */
@RunWith(RobolectricTestRunner::class)
class MediaViewerAccessibilityTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule val rule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private val items = listOf(
        TimelineItem("a1", MediaKind.IMAGE, "content://test/1", 0),
        TimelineItem("a2", MediaKind.IMAGE, "content://test/2", 0),
    )

    private fun show() {
        rule.setContent {
            IrohacoTheme(dynamicColor = false) {
                val paged = remember { flowOf(PagingData.from(items)) }.collectAsLazyPagingItems()
                MediaViewerContent(items = paged, startIndex = 0, onBack = {})
            }
        }
        rule.waitForIdle()
    }

    private fun actions() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        .fetchSemanticsNode().config[SemanticsActions.CustomActions]

    private fun labels() = actions().map { it.label }

    private fun perform(label: String) {
        rule.runOnIdle { actions().single { it.label == label }.action() }
        rule.waitForIdle()
    }

    @Test
    fun `the first photo offers the next one and zooming in`() {
        show()
        assertEquals(listOf("Next photo", "Zoom in"), labels())
    }

    @Test
    fun `the next photo action turns the page`() {
        show()
        perform("Next photo")
        assertEquals(listOf("Previous photo", "Zoom in"), labels())
        perform("Previous photo")
        assertEquals(listOf("Next photo", "Zoom in"), labels())
    }

    @Test
    fun `zooming in offers the way back`() {
        show()
        perform("Zoom in")
        assertEquals(listOf("Next photo", "Back to full view"), labels())
        perform("Back to full view")
        assertEquals(listOf("Next photo", "Zoom in"), labels())
    }
}
