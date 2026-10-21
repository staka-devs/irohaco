package dev.irohaco.feature.timeline

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.domain.LibraryAccess
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The top bar slides away while scrolling down and comes back on a scroll up (DESIGN §3, M1-27). */
@RunWith(RobolectricTestRunner::class)
class TopBarScrollTest {
    @get:Rule val rule = createComposeRule()

    private val items = (0 until 200).map { TimelineItem("a$it", MediaKind.IMAGE, "content://test/$it", 0) }

    private fun showGrid(selection: Map<String, TimelineItem> = emptyMap()) {
        rule.setContent {
            IrohacoTheme(dynamicColor = false) {
                val cells = remember { flowOf(PagingData.from(items).withDateHeaders(ZoneOffset.UTC)) }.collectAsLazyPagingItems()
                TimelineContent(MediaLibraryAccess(LibraryAccess.FULL, LibraryAccess.FULL), cells, {}, {}, selection = selection)
            }
        }
    }

    private fun bottomOf(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.bottom

    private fun scroll(down: Boolean) {
        rule.onNode(hasScrollAction()).performTouchInput { if (down) swipeUp() else swipeDown() }
        rule.waitForIdle()
    }

    @Test
    fun `hides the bar while scrolling down and shows it again on a scroll up`() {
        showGrid()
        val shown = bottomOf("Photos")
        scroll(down = true)
        assertTrue(bottomOf("Photos") < shown)
        scroll(down = false)
        assertEquals(shown, bottomOf("Photos"))
    }

    @Test
    fun `keeps the selection bar in view`() {
        showGrid(selection = mapOf("a0" to items[0]))
        val shown = bottomOf("1 selected")
        scroll(down = true)
        assertEquals(shown, bottomOf("1 selected"))
    }
}
