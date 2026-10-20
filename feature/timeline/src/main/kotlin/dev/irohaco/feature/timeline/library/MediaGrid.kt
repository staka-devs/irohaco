package dev.irohaco.feature.timeline.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.motion.sharedMedia
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.rememberDateFormatter
import java.time.Instant
import java.time.ZoneId

/** The grid of a folder, album, tag or the favorites; tapping opens the viewer over the same list. */
@Composable
internal fun MediaGrid(items: LazyPagingItems<TimelineItem>, padding: PaddingValues, onOpenMedia: (String) -> Unit) {
    val formatter = rememberDateFormatter()
    val zone = ZoneId.systemDefault()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs),
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count = items.itemCount, key = items.itemKey { it.assetId }) { index ->
            val item = items[index]
            if (item == null) {
                MediaThumbnail(model = null, contentDescription = "", selected = false)
            } else {
                val date = formatter.format(Instant.ofEpochMilli(item.sortTimeMs).atZone(zone))
                val description = stringResource(
                    if (item.kind == MediaKind.VIDEO) R.string.timeline_video_description else R.string.timeline_photo_description,
                    date,
                )
                MediaThumbnail(
                    model = item.contentUri,
                    contentDescription = description,
                    selected = false,
                    modifier = Modifier.sharedMedia(item.assetId).clickable { onOpenMedia(item.assetId) },
                )
            }
        }
    }
}
