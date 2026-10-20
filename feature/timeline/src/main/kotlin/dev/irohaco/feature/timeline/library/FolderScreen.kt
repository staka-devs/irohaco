package dev.irohaco.feature.timeline.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.irohaco.core.application.BrowseLibrary
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.designsystem.component.BadgeStatus
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.component.StatusBadge
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.motion.sharedMedia
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.TimelinePagingSource
import dev.irohaco.feature.timeline.rememberDateFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class FolderViewModel(getPage: GetTimelinePage, private val browse: BrowseLibrary, val folder: FolderRef) : ViewModel() {
    /** The timeline's order and kind, limited to this folder; exclusions do not hide the folder from itself. */
    val query: TimelineQuery = getPage.query.value.copy(folder = folder, excludedFolders = emptySet())

    private var source: TimelinePagingSource? = null

    val items: Flow<PagingData<TimelineItem>> =
        Pager(PagingConfig(pageSize = 120, enablePlaceholders = true)) { TimelinePagingSource(getPage, query).also { source = it } }
            .flow.cachedIn(viewModelScope)

    /** Null while unknown; the volume name and whether its card is out. */
    val volume: StateFlow<Pair<String, Boolean>?> = browse.folders()
        .map { folders -> folders.firstOrNull { it.folder == folder }?.let { it.volumeName to it.offline } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val excluded: StateFlow<Boolean> = browse.excludedFolders().map { folder in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch { getPage.changes().collect { source?.invalidate() } }
    }

    fun setExcluded(excluded: Boolean) {
        viewModelScope.launch { browse.setExcluded(folder, excluded) }
    }
}

/** One folder's photos and videos (PRD GAL-002). */
@Composable
fun FolderScreen(viewModel: FolderViewModel, onOpenMedia: (String) -> Unit, onBack: () -> Unit) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val excluded by viewModel.excluded.collectAsStateWithLifecycle()
    val path = viewModel.folder.relativePath
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.trimEnd('/').substringAfterLast('/').ifEmpty { volume?.let { volumeLabel(it.first) }.orEmpty() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) }
                },
                actions = {
                    // Shows what tapping does: hide this folder from the timeline, or show it again.
                    IconButton(onClick = { viewModel.setExcluded(!excluded) }) {
                        Icon(
                            painterResource(if (excluded) IrohacoIcons.Visibility else IrohacoIcons.VisibilityOff),
                            stringResource(if (excluded) R.string.library_include else R.string.library_exclude),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (volume?.second == true) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(IrohacoSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.l, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusBadge(BadgeStatus.Offline)
                Text(stringResource(R.string.library_offline_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        } else {
            FolderGrid(items, padding, onOpenMedia)
        }
    }
}

@Composable
private fun FolderGrid(items: LazyPagingItems<TimelineItem>, padding: PaddingValues, onOpenMedia: (String) -> Unit) {
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
