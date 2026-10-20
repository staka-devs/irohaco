package dev.irohaco.feature.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.domain.LibraryAccess
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/** Long press and drag on the grid (DESIGN §4.1). */
@RunWith(RobolectricTestRunner::class)
class TimelineGestureTest {
    @get:Rule val rule = createComposeRule()

    private val items = (0 until 6).map { TimelineItem("a$it", MediaKind.IMAGE, "content://test/$it", 0) }
    private var selection by mutableStateOf(emptyMap<String, TimelineItem>())
    private var opened = 0

    private fun showGrid() {
        rule.setContent {
            IrohacoTheme(dynamicColor = false) {
                val cells = remember { flowOf(PagingData.from(items).withDateHeaders(ZoneOffset.UTC)) }.collectAsLazyPagingItems()
                TimelineContent(
                    MediaLibraryAccess(LibraryAccess.FULL, LibraryAccess.FULL), cells, {}, {},
                    onOpenMedia = { opened++ },
                    selection = selection,
                    onSelectRange = { selection = it },
                )
            }
        }
    }

    private val photos get() = rule.onAllNodes(hasContentDescription("Photo", substring = true))

    @Test
    fun `a long press selects the cell without opening it`() {
        showGrid()
        photos[1].performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(listOf("a1"), selection.keys.toList())
        assertEquals(0, opened)
    }

    @Test
    fun `long press then drag selects the range`() {
        showGrid()
        val start = photos[0].fetchSemanticsNode().boundsInRoot
        val end = photos[2].fetchSemanticsNode().boundsInRoot.center
        photos[0].performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(end - start.topLeft)
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("a0", "a1", "a2"), selection.keys.toList())
        assertEquals(0, opened)
    }
}
