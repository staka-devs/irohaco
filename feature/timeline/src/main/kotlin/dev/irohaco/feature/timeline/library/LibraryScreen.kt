package dev.irohaco.feature.timeline.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.BrowseLibrary
import dev.irohaco.core.application.ManageCollections
import dev.irohaco.core.application.ReconcileIdentity
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.contracts.LibraryLayout
import dev.irohaco.core.contracts.Tag
import dev.irohaco.core.designsystem.component.BadgeStatus
import dev.irohaco.core.designsystem.component.StatusBadge
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.feature.timeline.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val browse: BrowseLibrary,
    collections: ManageCollections? = null,
    reconcile: ReconcileIdentity? = null,
) : ViewModel() {
    val candidateCount: StateFlow<Int> = (reconcile?.pending()?.map { it.size } ?: flowOf(0))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val tags: StateFlow<List<Tag>> =
        (collections?.tags() ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Null until the first read, so the screen does not flash "no folders". */
    val folders: StateFlow<List<LibraryFolder>?> =
        browse.folders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val excluded: StateFlow<Set<FolderRef>> =
        browse.excludedFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val pinned: StateFlow<List<FolderRef>> =
        browse.pinnedFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val layout: StateFlow<LibraryLayout> =
        browse.layout().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryLayout.TREE)

    fun setLayout(layout: LibraryLayout) {
        viewModelScope.launch { browse.setLayout(layout) }
    }

    fun movePinned(folder: FolderRef, by: Int) {
        viewModelScope.launch { browse.movePinned(folder, by) }
    }

    fun unpin(folder: FolderRef) {
        viewModelScope.launch { browse.setPinned(folder, false) }
    }
}

/** The pane key of a folder; a NUL cannot appear in a volume id or a path. */
internal fun FolderRef.key() = "$volumeId\u0000$relativePath"

internal fun folderOfKey(key: String) = key.split('\u0000', limit = 2).let { FolderRef(it[0], it.getOrElse(1) { "" }) }

/**
 * The Library tab (PRD GAL-002, GAL-011, DESIGN §4.4): the folders on the left and the open folder
 * on the right when the window is wide enough, one at a time otherwise. Back returns from the
 * folder to the list, and breadcrumbs move up within the folder pane.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    folderViewModel: @Composable (FolderRef) -> FolderViewModel,
    onOpenMedia: (assetId: String, folder: FolderRef) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCandidates: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    /** Opens the places from the location history, for photos without one (#213). */
    onOpenLocationHistory: () -> Unit = {},
) {
    val candidates by viewModel.candidateCount.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val excluded by viewModel.excluded.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val openFolder: (FolderRef) -> Unit = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, it.key()) } }
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                LibraryContent(
                    folders = folders,
                    excluded = excluded,
                    onOpenFolder = openFolder,
                    onOpenSettings = onOpenSettings,
                    tags = tags,
                    onOpenTag = onOpenTag,
                    candidates = candidates,
                    onOpenCandidates = onOpenCandidates,
                    onOpenTrash = onOpenTrash,
                    onOpenMap = onOpenMap,
                    onOpenLocationHistory = onOpenLocationHistory,
                    pinned = pinned,
                    onMovePinned = viewModel::movePinned,
                    onUnpin = viewModel::unpin,
                    layout = layout,
                    onLayout = viewModel::setLayout,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val key = navigator.currentDestination?.contentKey
                if (key == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.library_pick_folder), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    val folder = folderOfKey(key)
                    FolderPane(
                        viewModel = folderViewModel(folder),
                        showBack = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden,
                        onBack = { scope.launch { navigator.navigateBack() } },
                        onOpenFolder = openFolder,
                        onOpenMedia = { onOpenMedia(it, folder) },
                    )
                }
            }
        },
    )
}

@Composable
internal fun LibraryContent(
    folders: List<LibraryFolder>?,
    excluded: Set<FolderRef>,
    onOpenFolder: (FolderRef) -> Unit,
    onOpenSettings: () -> Unit,
    tags: List<Tag> = emptyList(),
    onOpenTag: (String) -> Unit = {},
    candidates: Int = 0,
    onOpenCandidates: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    onOpenLocationHistory: () -> Unit = {},
    pinned: List<FolderRef> = emptyList(),
    onMovePinned: (FolderRef, Int) -> Unit = { _, _ -> },
    onUnpin: (FolderRef) -> Unit = {},
    layout: LibraryLayout = LibraryLayout.TREE,
    onLayout: (LibraryLayout) -> Unit = {},
) {
    // Expanded folders as "volume NUL path"; saved so rotation keeps the tree as it was.
    val expanded = rememberSaveable(saver = listSaver<MutableState<Set<String>>, String>({ it.value.toList() }, { mutableStateOf(it.toSet()) })) {
        mutableStateOf(emptySet())
    }
    // Volumes start open, so their top-level folders show; this holds the ones the user folded.
    val collapsedVolumes = rememberSaveable(saver = listSaver<MutableState<Set<String>>, String>({ it.value.toList() }, { mutableStateOf(it.toSet()) })) {
        mutableStateOf(emptySet())
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    // Shows the layout a tap switches to.
                    if (layout == LibraryLayout.TREE) {
                        IconButton(onClick = { onLayout(LibraryLayout.GRID) }) {
                            Icon(painterResource(IrohacoIcons.GridView), stringResource(R.string.library_layout_grid))
                        }
                    } else {
                        IconButton(onClick = { onLayout(LibraryLayout.TREE) }) {
                            Icon(painterResource(IrohacoIcons.ViewList), stringResource(R.string.library_layout_tree))
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(IrohacoIcons.Settings), stringResource(R.string.timeline_settings))
                    }
                },
            )
        },
    ) { padding ->
        if (folders?.isEmpty() == true) {
            Text(
                stringResource(R.string.library_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(padding).padding(IrohacoSpacing.xxl),
            )
            return@Scaffold
        }
        val all = folders.orEmpty()
        val volumes = all.groupBy { it.folder.volumeId }.mapValues { (_, list) -> list.first() to folderTree(list) }
        val pinnedLines = pinned.mapNotNull { ref ->
            volumes[ref.volumeId]?.let { (volume, lines) -> lines.firstOrNull { it.path == ref.relativePath }?.let { Triple(ref, volume, it) } }
        }
        val contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
        when (layout) {
            LibraryLayout.TREE -> LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                header(candidates, onOpenCandidates, onOpenTrash, onOpenMap, onOpenLocationHistory)
                pinnedSection(pinnedLines, onOpenFolder, onMovePinned, onUnpin)
                for ((volumeId, entry) in volumes) {
                    val (volume, lines) = entry
                    val volumeOpen = volumeId !in collapsedVolumes.value
                    item(key = "volume-$volumeId") {
                        VolumeNode(
                            volumeName = volume.volumeName,
                            offline = volume.offline,
                            itemCount = lines.sumOf { it.folder?.itemCount ?: 0 },
                            expanded = volumeOpen,
                            onToggle = {
                                collapsedVolumes.value = if (volumeOpen) collapsedVolumes.value + volumeId else collapsedVolumes.value - volumeId
                            },
                            onOpen = { onOpenFolder(FolderRef(volumeId, "")) },
                        )
                    }
                    if (!volumeOpen) continue
                    val open = expanded.value.filter { it.startsWith("$volumeId\u0000") }.map { it.substringAfter('\u0000') }.toSet()
                    items(visibleLines(lines, open), key = { "$volumeId/${it.path}" }) { line ->
                        val key = FolderRef(volumeId, line.path).key()
                        TreeItem(
                            line = line,
                            volumeName = volume.volumeName,
                            excluded = FolderRef(volumeId, line.path) in excluded,
                            expanded = line.path in open,
                            onToggle = { expanded.value = if (key in expanded.value) expanded.value - key else expanded.value + key },
                            onOpen = { onOpenFolder(FolderRef(volumeId, line.path)) },
                        )
                    }
                }
                tagSection(tags, onOpenTag)
            }
            LibraryLayout.GRID -> LazyVerticalGrid(
                columns = GridCells.Adaptive(152.dp),
                contentPadding = PaddingValues(
                    start = IrohacoSpacing.l,
                    end = IrohacoSpacing.l,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
                verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.l),
                modifier = Modifier.fillMaxSize(),
            ) {
                fullLine("candidates-and-pinned") {
                    Column {
                        MapItem(onOpenMap)
                        UnlocatedItem(onOpenLocationHistory)
                        TrashItem(onOpenTrash)
                        if (candidates > 0) CandidatesItem(candidates, onOpenCandidates)
                        pinnedLines.forEachIndexed { index, (ref, volume, line) ->
                            PinnedItem(ref, volume, line, index, pinnedLines.size, onOpenFolder, onMovePinned, onUnpin)
                        }
                    }
                }
                for ((volumeId, entry) in volumes) {
                    val (volume, lines) = entry
                    fullLine("volume-$volumeId") {
                        VolumeNode(
                            volumeName = volume.volumeName,
                            offline = volume.offline,
                            itemCount = lines.sumOf { it.folder?.itemCount ?: 0 },
                            expanded = null,
                            onToggle = {},
                            onOpen = { onOpenFolder(FolderRef(volumeId, "")) },
                        )
                    }
                    // The top-level folders; deeper ones open from their parent, and the volume's own files from the volume.
                    val top = lines.filter { it.depth == 1 }
                    items(top.size, key = { "$volumeId/${top[it].path}" }) { index ->
                        val line = top[index]
                        CoverCard(
                            title = line.name,
                            count = line.totalCount,
                            coverUri = line.coverUri,
                        ) { onOpenFolder(FolderRef(volumeId, line.path)) }
                    }
                }
                if (tags.isNotEmpty()) {
                    fullLine("tags") {
                        Column {
                            Text(
                                stringResource(R.string.library_tags),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
                            )
                            tags.forEach { TagItem(it, onOpenTag) }
                        }
                    }
                }
            }
        }
    }
}

/** A row across the whole grid; it bleeds over the grid's side padding, as list items bring their own. */
private fun LazyGridScope.fullLine(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Box(
            Modifier.layout { measurable, constraints ->
                val extra = (IrohacoSpacing.l * 2).roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
            },
        ) { content() }
    }
}

private fun LazyListScope.header(
    candidates: Int,
    onOpenCandidates: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenLocationHistory: () -> Unit,
) {
    item(key = "map") { MapItem(onOpenMap) }
    item(key = "unlocated") { UnlocatedItem(onOpenLocationHistory) }
    item(key = "trash") { TrashItem(onOpenTrash) }
    if (candidates > 0) item(key = "candidates") { CandidatesItem(candidates, onOpenCandidates) }
}

private fun LazyListScope.pinnedSection(
    pinned: List<Triple<FolderRef, LibraryFolder, FolderLine>>,
    onOpen: (FolderRef) -> Unit,
    onMove: (FolderRef, Int) -> Unit,
    onUnpin: (FolderRef) -> Unit,
) {
    if (pinned.isEmpty()) return
    item(key = "pinned") { SectionTitle(R.string.library_pinned) }
    pinned.forEachIndexed { index, (ref, volume, line) ->
        item(key = "pinned-${ref.key()}") { PinnedItem(ref, volume, line, index, pinned.size, onOpen, onMove, onUnpin) }
    }
}

private fun LazyListScope.tagSection(tags: List<Tag>, onOpenTag: (String) -> Unit) {
    if (tags.isEmpty()) return
    item(key = "tags") { SectionTitle(R.string.library_tags) }
    items(tags, key = { "tag-${it.tagId}" }) { TagItem(it, onOpenTag) }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
    )
}

@Composable
private fun CandidatesItem(candidates: Int, onOpen: () -> Unit) {
    ListItem(
        supportingContent = { Text(stringResource(R.string.identity_check)) },
        leadingContent = { Icon(painterResource(IrohacoIcons.FindReplace), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onOpen),
    ) { Text(pluralStringResource(R.plurals.identity_found, candidates, candidates)) }
}

@Composable
private fun MapItem(onOpen: () -> Unit) {
    ListItem(
        leadingContent = { Icon(painterResource(IrohacoIcons.Map), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onOpen),
    ) { Text(stringResource(R.string.library_map)) }
}

@Composable
private fun UnlocatedItem(onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.library_unlocated)) },
        leadingContent = { Icon(painterResource(IrohacoIcons.LocationOn), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun TrashItem(onOpen: () -> Unit) {
    ListItem(
        leadingContent = { Icon(painterResource(IrohacoIcons.Delete), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onOpen),
    ) { Text(stringResource(R.string.library_trash)) }
}

@Composable
private fun TagItem(tag: Tag, onOpenTag: (String) -> Unit) {
    ListItem(
        supportingContent = { Text(pluralStringResource(R.plurals.library_items, tag.itemCount, tag.itemCount)) },
        leadingContent = { Icon(painterResource(IrohacoIcons.Label), contentDescription = null) },
        modifier = Modifier.clickable { onOpenTag(tag.tagId) },
    ) { Text(tag.name) }
}

/** A pinned folder; its menu moves it within the pinned list or unpins it. */
@Composable
private fun PinnedItem(
    ref: FolderRef,
    volume: LibraryFolder,
    line: FolderLine,
    index: Int,
    count: Int,
    onOpen: (FolderRef) -> Unit,
    onMove: (FolderRef, Int) -> Unit,
    onUnpin: (FolderRef) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val name = line.name.ifEmpty { volumeLabel(volume.volumeName) }
    ListItem(
        supportingContent = { Text(volumeLabel(volume.volumeName) + "/" + ref.relativePath) },
        leadingContent = { Icon(painterResource(IrohacoIcons.PushPinFilled), contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(IrohacoIcons.MoreVert), stringResource(R.string.library_pinned_menu, name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_move_up)) },
                        enabled = index > 0,
                        onClick = {
                            menu = false
                            onMove(ref, -1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_move_down)) },
                        enabled = index < count - 1,
                        onClick = {
                            menu = false
                            onMove(ref, 1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_unpin)) },
                        onClick = {
                            menu = false
                            onUnpin(ref)
                        },
                    )
                }
            }
        },
        modifier = Modifier.clickable { onOpen(ref) },
    ) { Text(name) }
}

@Composable
internal fun volumeLabel(volumeName: String): String =
    if (volumeName == PRIMARY_VOLUME) stringResource(R.string.library_internal) else stringResource(R.string.library_sd_card, volumeName)

private const val PRIMARY_VOLUME = "external_primary"

/**
 * The root of a volume's tree, like a drive in a file manager: tapping opens the files at the top of
 * the volume, and the toggle (when [expanded] is not null) folds the top-level folders below it.
 */
@Composable
private fun VolumeNode(
    volumeName: String,
    offline: Boolean,
    itemCount: Int,
    expanded: Boolean?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val label = volumeLabel(volumeName)
    ListItem(
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.library_items, itemCount, itemCount))
                // Offline is a label and an icon, never a greyed-out row (DESIGN §4.4).
                if (offline) StatusBadge(BadgeStatus.Offline)
            }
        },
        leadingContent = { Icon(painterResource(if (offline) IrohacoIcons.SdCardAlert else IrohacoIcons.Folder), contentDescription = null) },
        trailingContent = expanded?.let { open ->
            {
                IconButton(onClick = onToggle) {
                    Icon(
                        painterResource(if (open) IrohacoIcons.ExpandMore else IrohacoIcons.ChevronRight),
                        stringResource(if (open) R.string.library_collapse else R.string.library_expand, label),
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

/** One folder of the tree; folders with subfolders get a toggle that unfolds them in place. */
@Composable
private fun TreeItem(
    line: FolderLine,
    volumeName: String,
    excluded: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val name = line.name
    ListItem(
        supportingContent = {
            val count = pluralStringResource(R.plurals.library_items, line.totalCount, line.totalCount)
            Text(if (excluded) stringResource(R.string.library_excluded, count) else count)
        },
        leadingContent = {
            Icon(painterResource(if (excluded) IrohacoIcons.VisibilityOff else IrohacoIcons.Folder), contentDescription = null)
        },
        trailingContent = if (line.hasChildren) {
            {
                IconButton(onClick = onToggle) {
                    Icon(
                        painterResource(if (expanded) IrohacoIcons.ExpandMore else IrohacoIcons.ChevronRight),
                        stringResource(if (expanded) R.string.library_collapse else R.string.library_expand, name),
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier
            .padding(start = IrohacoSpacing.l * line.depth) // the volume node is level 0
            .clickable(onClick = onOpen),
    ) { Text(name) }
}

private val previewFolders = listOf(
    LibraryFolder(FolderRef("sd", "DCIM/"), "1234-5678", offline = true, itemCount = 120),
    LibraryFolder(FolderRef("in", ""), "external_primary", offline = false, itemCount = 2),
    LibraryFolder(FolderRef("in", "DCIM/Camera/"), "external_primary", offline = false, itemCount = 1_204),
    LibraryFolder(FolderRef("in", "Pictures/Screenshots/"), "external_primary", offline = false, itemCount = 37),
    LibraryFolder(FolderRef("in", "Pictures/Trip/2026/"), "external_primary", offline = false, itemCount = 12),
)

@IrohacoPreviews
@Composable
private fun LibraryPreview() {
    IrohacoTheme(dynamicColor = false) {
        LibraryContent(
            folders = previewFolders,
            excluded = setOf(FolderRef("in", "Pictures/Screenshots/")),
            onOpenFolder = {},
            onOpenSettings = {},
            tags = listOf(Tag("t1", "Trip", 24)),
            pinned = listOf(FolderRef("in", "DCIM/Camera/")),
        )
    }
}

@IrohacoPreviews
@Composable
private fun LibraryGridPreview() {
    IrohacoTheme(dynamicColor = false) {
        LibraryContent(folders = previewFolders, excluded = emptySet(), onOpenFolder = {}, onOpenSettings = {}, layout = LibraryLayout.GRID)
    }
}
