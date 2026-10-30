package dev.irohaco.feature.timeline.viewer

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The viewer's "more" menu (PRD GAL-013). */
@RunWith(RobolectricTestRunner::class)
class MediaViewerMenuTest {
    // The v2 rule defers effects, and the paged item then came too late on CI.
    @Suppress("DEPRECATION")
    @get:Rule val rule = createComposeRule()

    private val item = TimelineItem("a1", MediaKind.IMAGE, "content://test/1", 0)
    private var trashed: TimelineItem? = null
    private var shownOnMap = 0

    private fun showMenu(hasLocation: Boolean) {
        rule.setContent {
            IrohacoTheme(dynamicColor = false) {
                val items = remember { flowOf(PagingData.from(listOf(item))) }.collectAsLazyPagingItems()
                MediaViewerContent(
                    items = items,
                    startIndex = 0,
                    onBack = {},
                    hasLocation = hasLocation,
                    onTrash = { trashed = it },
                    onShowOnMap = { shownOnMap++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("More").performClick()
    }

    @Test
    fun `offers to show the item on a map only when it has a location`() {
        showMenu(hasLocation = false)
        rule.onAllNodesWithText("Show on map").assertCountEquals(0)
    }

    @Test
    fun `tapping show on map in the menu calls back for the current item`() {
        showMenu(hasLocation = true)
        rule.onNodeWithText("Show on map").performClick()
        assertEquals(1, shownOnMap)
    }

    @Test
    fun `tapping delete in the menu moves the current item to the trash`() {
        showMenu(hasLocation = true)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(item, trashed)
    }
}
