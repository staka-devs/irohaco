package dev.irohaco.feature.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.domain.LibraryAccess
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/** Long press and drag on the grid (DESIGN §4.1). */
@RunWith(RobolectricTestRunner::class)
class TimelineGestureTest {
    @get:Rule val rule = createComposeRule()

    private var items = (0 until 6).map { TimelineItem("a$it", MediaKind.IMAGE, "content://test/$it", 0) }
    private var selection by mutableStateOf(emptyMap<String, TimelineItem>())
    private var opened = 0
    private var columns by mutableStateOf<Int?>(null)

    private fun showGrid() {
        rule.setContent {
            IrohacoTheme(dynamicColor = false) {
                val cells = remember { flowOf(PagingData.from(items).withDateHeaders(ZoneOffset.UTC)) }.collectAsLazyPagingItems()
                TimelineContent(
                    MediaLibraryAccess(LibraryAccess.FULL, LibraryAccess.FULL), cells, {}, {},
                    onOpenMedia = { opened++ },
                    selection = selection,
                    onSelectRange = { selection = it },
                    chosenColumns = columns,
                    onColumns = { columns = it },
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

    @Test
    fun `pinching in from four columns keeps shrinking the cells`() {
        items = (0 until 500).map { TimelineItem("a$it", MediaKind.IMAGE, "content://test/$it", it * 600_000L) }
        showGrid()
        var width = photos[0].fetchSemanticsNode().boundsInRoot.width
        repeat(3) {
            rule.onRoot().performTouchInput {
                pinch(center - Offset(140f, 0f), center - Offset(10f, 0f), center + Offset(140f, 0f), center + Offset(10f, 0f))
            }
            rule.waitForIdle()
            val now = photos[0].fetchSemanticsNode().boundsInRoot.width
            assertTrue("$now < $width", now < width)
            width = now
        }
    }
}
