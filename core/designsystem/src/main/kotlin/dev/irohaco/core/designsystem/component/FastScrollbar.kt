package dev.irohaco.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A right-edge scroll thumb for very long grids (PRD GAL-014, NFR-002): visible while scrolling and
 * for a moment after, draggable to any position in the whole grid including items not loaded yet,
 * with a bubble naming the destination while dragging. TalkBack never reaches it — it keeps using
 * the grid's own scrolling, which does the same thing one item at a time.
 */
@Composable
fun FastScrollbar(
    gridState: LazyGridState,
    itemCount: Int,
    label: String?,
    onDragIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (itemCount == 0) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var thumbOffsetPx by remember { mutableFloatStateOf(0f) }
    val thumbHeightPx = with(LocalDensity.current) { THUMB_HEIGHT.toPx() }

    // Follows normal scrolling too, so the thumb always shows roughly where the grid is.
    LaunchedEffect(gridState, itemCount, trackHeightPx) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { index ->
            if (!dragging) thumbOffsetPx = offsetFor(index, itemCount, trackHeightPx, thumbHeightPx)
        }
    }
    LaunchedEffect(gridState.isScrollInProgress, dragging) {
        if (gridState.isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(HIDE_DELAY_MS)
            visible = false
        }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.fillMaxHeight()) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(TOUCH_WIDTH)
                .onSizeChanged { trackHeightPx = it.height.toFloat() }
                .pointerInput(itemCount, trackHeightPx) {
                    fun jumpTo(y: Float) {
                        thumbOffsetPx = y.coerceIn(0f, (trackHeightPx - thumbHeightPx).coerceAtLeast(0f))
                        val index = indexFor(thumbOffsetPx, trackHeightPx, thumbHeightPx, itemCount)
                        onDragIndex(index)
                        scope.launch { gridState.scrollToItem(index) }
                    }
                    detectDragGestures(
                        onDragStart = { offset -> dragging = true; jumpTo(offset.y - thumbHeightPx / 2) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> jumpTo(change.position.y - thumbHeightPx / 2) }
                }
                // TalkBack users scroll the grid itself instead; this thumb would only confuse that.
                .clearAndSetSemantics {},
        ) {
            ScrollbarThumb(thumbOffsetPx, if (dragging) label else null)
        }
    }
}

@Composable
private fun BoxScope.ScrollbarThumb(thumbOffsetPx: Float, label: String?) {
    label?.let {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                // The track is only the touch width; the bubble sizes to its text and grows leftwards out of it.
                .wrapContentWidth(Alignment.End, unbounded = true)
                .offset { IntOffset(-(TOUCH_WIDTH + IrohacoSpacing.xs).roundToPx(), thumbOffsetPx.roundToInt()) },
        ) {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = IrohacoSpacing.m, vertical = IrohacoSpacing.s),
            )
        }
    }
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
            .padding(end = IrohacoSpacing.s)
            .width(THUMB_WIDTH)
            .height(THUMB_HEIGHT)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
    )
}

internal fun offsetFor(index: Int, itemCount: Int, trackHeightPx: Float, thumbHeightPx: Float): Float {
    val range = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    return (index.toFloat() / (itemCount - 1).coerceAtLeast(1)) * range
}

internal fun indexFor(offsetPx: Float, trackHeightPx: Float, thumbHeightPx: Float, itemCount: Int): Int {
    val range = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val fraction = (offsetPx / range).coerceIn(0f, 1f)
    return (fraction * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
}

/** Touch target per DESIGN.md (48dp minimum); the visible thumb is a thin bar inside it. */
private val TOUCH_WIDTH = 48.dp
private val THUMB_WIDTH = 4.dp
private val THUMB_HEIGHT = 48.dp
private const val HIDE_DELAY_MS = 1_200L

@IrohacoPreviews
@Composable
private fun FastScrollbarPreview() {
    IrohacoTheme(dynamicColor = false) {
        Box(Modifier.width(240.dp).height(320.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            ScrollbarThumb(thumbOffsetPx = with(LocalDensity.current) { 120.dp.toPx() }, label = "2026/07/15")
        }
    }
}
