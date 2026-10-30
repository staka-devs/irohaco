package dev.irohaco.feature.timeline.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import dev.irohaco.core.application.BrowseLibrary
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineLabel
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.designsystem.component.BadgeStatus
import dev.irohaco.core.designsystem.component.StatusBadge
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.TimelinePagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FolderViewModel(private val getPage: GetTimelinePage, private val browse: BrowseLibrary, val folder: FolderRef) : ViewModel() {
    /** The timeline's order and kind, limited to this folder; exclusions do not hide the folder from itself. */
    val query: TimelineQuery = MediaList.Folder(folder).query(getPage.query.value)

    private var source: TimelinePagingSource? = null

    val items: Flow<PagingData<TimelineItem>> =
        Pager(PagingConfig(pageSize = 120, enablePlaceholders = true)) { TimelinePagingSource(getPage, query).also { source = it } }
            .flow.cachedIn(viewModelScope)

    /** What the fast scrollbar's bubble names at this position (PRD GAL-014). */
    suspend fun labelAt(index: Int) = getPage.labelAt(index, query)

    private val volumeFolders = browse.folders().map { all -> all.filter { it.folder.volumeId == folder.volumeId } }

    /** Null while unknown; the volume name and whether its card is out. */
    val volume: StateFlow<Pair<String, Boolean>?> = volumeFolders
        .map { folders -> folders.firstOrNull()?.let { it.volumeName to it.offline } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The subfolders, listed before the photos and videos (GAL-011). */
    internal val children: StateFlow<List<FolderLine>> = volumeFolders
        .map { childrenOf(folderTree(it), folder.relativePath) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val excluded: StateFlow<Boolean> = browse.excludedFolders().map { folder in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pinned: StateFlow<Boolean> = browse.pinnedFolders().map { folder in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch { getPage.changes().collect { source?.invalidate() } }
    }

    fun setExcluded(excluded: Boolean) {
        viewModelScope.launch { browse.setExcluded(folder, excluded) }
    }

    fun setPinned(pinned: Boolean) {
        viewModelScope.launch { browse.setPinned(folder, pinned) }
    }
}

/** One folder: breadcrumbs, its subfolders, then its photos and videos (PRD GAL-002, GAL-011). */
@Composable
internal fun FolderPane(
    viewModel: FolderViewModel,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenFolder: (FolderRef) -> Unit,
    onOpenMedia: (String) -> Unit,
) {
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val children by viewModel.children.collectAsStateWithLifecycle()
    val excluded by viewModel.excluded.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    FolderContent(
        folder = viewModel.folder,
        volumeName = volume?.first,
        offline = volume?.second == true,
        children = children,
        items = viewModel.items.collectAsLazyPagingItems(),
        excluded = excluded,
        pinned = pinned,
        showBack = showBack,
        onBack = onBack,
        onOpenFolder = onOpenFolder,
        onOpenMedia = onOpenMedia,
        onExclude = viewModel::setExcluded,
        onPin = viewModel::setPinned,
        labelAt = viewModel::labelAt,
    )
}

@Composable
internal fun FolderContent(
    folder: FolderRef,
    volumeName: String?,
    offline: Boolean,
    children: List<FolderLine>,
    items: LazyPagingItems<TimelineItem>,
    excluded: Boolean,
    pinned: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenFolder: (FolderRef) -> Unit,
    onOpenMedia: (String) -> Unit,
    onExclude: (Boolean) -> Unit,
    onPin: (Boolean) -> Unit,
    labelAt: suspend (Int) -> TimelineLabel? = { null },
) {
    val rootLabel = volumeName?.let { volumeLabel(it) }.orEmpty()
    val crumbs = breadcrumbs(folder.relativePath)
    // The bar slides away while scrolling down and comes back on any scroll up (DESIGN §3, M1-27).
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(crumbs.lastOrNull()?.first ?: rootLabel) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) }
                    }
                },
                actions = {
                    // Each button shows what a tap does.
                    IconButton(onClick = { onPin(!pinned) }) {
                        Icon(
                            painterResource(if (pinned) IrohacoIcons.PushPinFilled else IrohacoIcons.PushPin),
                            stringResource(if (pinned) R.string.library_unpin else R.string.library_pin),
                        )
                    }
                    IconButton(onClick = { onExclude(!excluded) }) {
                        Icon(
                            painterResource(if (excluded) IrohacoIcons.Visibility else IrohacoIcons.VisibilityOff),
                            stringResource(if (excluded) R.string.library_include else R.string.library_exclude),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (offline) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(IrohacoSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.l, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusBadge(BadgeStatus.Offline)
                Text(stringResource(R.string.library_offline_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        MediaGrid(items, padding, onOpenMedia, labelAt = labelAt) {
            if (crumbs.isNotEmpty()) {
                item(key = "breadcrumbs", span = { GridItemSpan(maxLineSpan) }) {
                    Breadcrumbs(rootLabel, crumbs) { onOpenFolder(FolderRef(folder.volumeId, it)) }
                }
            }
            for (child in children) {
                item(key = "folder-${child.path}", span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        supportingContent = { Text(pluralStringResource(R.plurals.library_items, child.totalCount, child.totalCount)) },
                        leadingContent = { Icon(painterResource(IrohacoIcons.Folder), contentDescription = null) },
                        modifier = Modifier.clickable { onOpenFolder(FolderRef(folder.volumeId, child.path)) },
                    ) { Text(child.name) }
                }
            }
        }
    }
}

/** The volume, then each folder down to this one; tapping a step opens that folder. The last step is where you are. */
@Composable
private fun Breadcrumbs(rootLabel: String, crumbs: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = IrohacoSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen("") }) { Text(rootLabel) }
        crumbs.forEachIndexed { index, (name, path) ->
            Icon(painterResource(IrohacoIcons.ChevronRight), contentDescription = null)
            if (index == crumbs.lastIndex) {
                Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = IrohacoSpacing.m))
            } else {
                TextButton(onClick = { onOpen(path) }) { Text(name) }
            }
        }
    }
}

@IrohacoPreviews
@Composable
private fun FolderPreview() {
    IrohacoTheme(dynamicColor = false) {
        FolderContent(
            folder = FolderRef("in", "Pictures/Trip/"),
            volumeName = "external_primary",
            offline = false,
            children = listOf(FolderLine("2026", 3, "Pictures/Trip/2026/", null, totalCount = 12)),
            items = flowOf(PagingData.from(List(5) { TimelineItem("a$it", dev.irohaco.core.domain.MediaKind.IMAGE, "content://preview/$it", 0) }))
                .collectAsLazyPagingItems(),
            excluded = false,
            pinned = true,
            showBack = true,
            onBack = {},
            onOpenFolder = {},
            onOpenMedia = {},
            onExclude = {},
            onPin = {},
        )
    }
}
