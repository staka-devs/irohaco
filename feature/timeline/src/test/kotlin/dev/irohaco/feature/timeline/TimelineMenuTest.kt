package dev.irohaco.feature.timeline

import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** The top bar's overflow menu (M1-30): the map, and the column count without a pinch. */
@RunWith(RobolectricTestRunner::class)
class TimelineMenuTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `the menu opens the map`() {
        var opened = 0
        rule.setContent {
            IrohacoTheme(dynamicColor = false) {
                val items = (0 until 3).map { TimelineItem("a$it", MediaKind.IMAGE, "content://test/$it", 0) }
                val cells = remember { flowOf(PagingData.from(items).withDateHeaders(ZoneOffset.UTC)) }.collectAsLazyPagingItems()
                TimelineContent(MediaLibraryAccess(LibraryAccess.FULL, LibraryAccess.FULL), cells, {}, {}, onOpenMap = { opened++ })
            }
        }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Show on map").performClick()
        assertEquals(1, opened)
    }
}
