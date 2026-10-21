package dev.irohaco.feature.timeline.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineLabel
import dev.irohaco.core.designsystem.component.FastScrollbar
import dev.irohaco.core.designsystem.component.GridThumbnail
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.motion.sharedMedia
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.feature.timeline.badge
import dev.irohaco.feature.timeline.badgeIcon
import dev.irohaco.feature.timeline.PrefetchThumbnails
import dev.irohaco.feature.timeline.SCRUB_DATE
import dev.irohaco.feature.timeline.description
import dev.irohaco.feature.timeline.format
import dev.irohaco.feature.timeline.rememberDateFormatter
import java.time.Instant
import java.time.ZoneId

/** The grid of a folder, album, tag or the favorites; tapping opens the viewer over the same list. */
@Composable
internal fun MediaGrid(
    items: LazyPagingItems<TimelineItem>,
    padding: PaddingValues,
    onOpenMedia: (String) -> Unit,
    labelAt: suspend (Int) -> TimelineLabel? = { null },
    header: LazyGridScope.() -> Unit = {},
) {
    val formatter = rememberDateFormatter()
    val scrubFormatter = rememberDateFormatter(SCRUB_DATE)
    val zone = ZoneId.systemDefault()
    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            state = gridState,
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs),
            modifier = Modifier.fillMaxSize(),
        ) {
            header()
            items(count = items.itemCount, key = items.itemKey { it.assetId }) { index ->
                val item = items[index]
                if (item == null) {
                    MediaThumbnail(model = null, contentDescription = "", selected = false)
                } else {
                    val date = formatter.format(Instant.ofEpochMilli(item.sortTimeMs).atZone(zone))
                    MediaThumbnail(
                        model = GridThumbnail(item.contentUri),
                        contentDescription = item.description(date),
                        selected = false,
                        label = item.badge,
                        labelIcon = item.badgeIcon,
                        modifier = Modifier.sharedMedia(item.assetId).clickable { onOpenMedia(item.assetId) },
                    )
                }
            }
        }
        PrefetchThumbnails(gridState) { index ->
            // Grid indices count the header items first.
            val itemIndex = index - (gridState.layoutInfo.totalItemsCount - items.itemCount)
            if (itemIndex in 0..<items.itemCount) items.peek(itemIndex)?.contentUri else null
        }
        val context = LocalContext.current
        var dragIndex by remember { mutableStateOf<Int?>(null) }
        val label by produceState<String?>(null, dragIndex) { value = dragIndex?.let { labelAt(it)?.format(scrubFormatter, zone, context) } }
        FastScrollbar(
            gridState = gridState,
            itemCount = items.itemCount,
            label = label,
            onDragIndex = { dragIndex = it },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}
