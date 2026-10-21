package dev.irohaco.feature.timeline

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import dev.irohaco.core.designsystem.component.GridThumbnail
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Loads one screenful of thumbnails past the edge the grid is scrolling towards, so cells are
 * already in the memory cache when they come into view (M1-25).
 *
 * @param uriAt the content Uri of the grid item at an index, or null for headers and pages not loaded yet.
 */
@Composable
internal fun PrefetchThumbnails(state: LazyGridState, flinging: Boolean = false, uriAt: (Int) -> String?) {
    val context = LocalContext.current
    val currentUriAt by rememberUpdatedState(uriAt)
    val currentFlinging by rememberUpdatedState(flinging)
    LaunchedEffect(state) {
        var previous = state.firstVisibleItemIndex
        snapshotFlow { state.firstVisibleItemIndex }.collect { first ->
            val visible = state.layoutInfo.visibleItemsInfo
            // A fling passes whole screens without stopping; reading ahead then only competes with drawing.
            if (visible.isEmpty() || currentFlinging) {
                previous = first
                return@collect
            }
            val ahead = prefetchRange(first, previous, visible.first().index, visible.last().index)
            previous = first
            for (index in ahead) {
                currentUriAt(index)?.let { context.imageLoader.enqueue(ImageRequest.Builder(context).data(GridThumbnail(it)).build()) }
            }
        }
    }
}

/**
 * The indices just past the visible ones in the scroll direction, as many as are visible; none after a
 * jump of several screens (a fast scrollbar drag), whose direction says nothing about what comes next.
 */
internal fun prefetchRange(first: Int, previous: Int, firstVisible: Int, lastVisible: Int): IntRange {
    val count = lastVisible - firstVisible + 1
    if (abs(first - previous) > count * 2) return IntRange.EMPTY
    return if (first >= previous) lastVisible + 1..lastVisible + count else (firstVisible - count).coerceAtLeast(0)..<firstVisible
}

/**
 * True while the grid moves faster than about a third of a screen per tenth of a second, as in a
 * fling (M1-34). Cells then show only what is already in memory, and the rest load once it slows.
 */
@Composable
internal fun rememberFlinging(state: LazyGridState): State<Boolean> {
    val fast = remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            fast.value = false
            if (!scrolling) return@collectLatest
            var last = state.firstVisibleItemIndex
            while (true) {
                delay(FLING_SAMPLE_MS)
                val now = state.firstVisibleItemIndex
                fast.value = isFast(now - last, state.layoutInfo.visibleItemsInfo.size)
                last = now
            }
        }
    }
    return fast
}

/** Moving more than a third of the visible cells in one sample counts as a fling. */
internal fun isFast(moved: Int, visible: Int): Boolean = abs(moved) * 3 > visible.coerceAtLeast(1)

private const val FLING_SAMPLE_MS = 100L

/** A cell's image: while [flinging], only one already in memory, so no new decode competes with drawing. */
@Composable
internal fun gridModel(contentUri: String, flinging: Boolean): GridThumbnail? {
    val thumbnail = GridThumbnail(contentUri)
    if (!flinging) return thumbnail
    val loader = LocalContext.current.imageLoader
    return thumbnail.takeIf { loader.memoryCache?.get(MemoryCache.Key(it.memoryKey)) != null }
}
