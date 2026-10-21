package dev.irohaco.feature.timeline

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import dev.irohaco.core.designsystem.component.GridThumbnail

/**
 * Loads one screenful of thumbnails past the edge the grid is scrolling towards, so cells are
 * already in the memory cache when they come into view (M1-25).
 *
 * @param uriAt the content Uri of the grid item at an index, or null for headers and pages not loaded yet.
 */
@Composable
internal fun PrefetchThumbnails(state: LazyGridState, uriAt: (Int) -> String?) {
    val context = LocalContext.current
    val currentUriAt by rememberUpdatedState(uriAt)
    LaunchedEffect(state) {
        var previous = state.firstVisibleItemIndex
        snapshotFlow { state.firstVisibleItemIndex }.collect { first ->
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@collect
            val ahead = prefetchRange(first, previous, visible.first().index, visible.last().index)
            previous = first
            for (index in ahead) {
                currentUriAt(index)?.let { context.imageLoader.enqueue(ImageRequest.Builder(context).data(GridThumbnail(it)).build()) }
            }
        }
    }
}

/** The indices just past the visible ones in the scroll direction, as many as are visible. */
internal fun prefetchRange(first: Int, previous: Int, firstVisible: Int, lastVisible: Int): IntRange {
    val count = lastVisible - firstVisible + 1
    return if (first >= previous) lastVisible + 1..lastVisible + count else (firstVisible - count).coerceAtLeast(0)..<firstVisible
}
