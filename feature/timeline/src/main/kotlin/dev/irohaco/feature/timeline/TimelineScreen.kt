package dev.irohaco.feature.timeline

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import dev.irohaco.core.contracts.FileOperationResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.pluralStringResource
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.motion.sharedMedia
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.LibraryAccess
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import dev.irohaco.core.contracts.TimelineQuery
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val MEDIA_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    }

/** The Photos tab (PRD GAL-001, DESIGN §4.1). */
@Composable
fun TimelineScreen(viewModel: TimelineViewModel, onOpenMedia: (assetId: String) -> Unit, onOpenSettings: () -> Unit) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    val cells = viewModel.cells.collectAsLazyPagingItems()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refreshAccess() }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }
    // Trash and restore go through the system's confirmation; the resume-time catalog sync then updates the grid.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var trashed by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val trashedMessage = pluralStringResource(R.plurals.timeline_trashed, trashed.size, trashed.size)
    val undoLabel = stringResource(R.string.timeline_undo)
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        viewModel.clearSelection()
        val uris = trashed
        scope.launch {
            val answer = snackbar.showSnackbar(trashedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
            if (answer == SnackbarResult.ActionPerformed) restore.launch(trashRequest(context, uris, trashed = false))
        }
    }
    // Move and rename change files other apps own, so the system asks the user first; copy only reads.
    var pending by remember { mutableStateOf<Pending?>(null) }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val deniedMessage = stringResource(R.string.timeline_change_denied)
    val notFoundMessage = stringResource(R.string.timeline_not_found)
    val resources = LocalResources.current
    val write = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) afterGrant?.invoke() else scope.launch { snackbar.showSnackbar(deniedMessage) }
        afterGrant = null
    }
    fun withWriteGrant(items: List<TimelineItem>, action: () -> Unit) {
        afterGrant = action
        write.launch(writeRequest(context, items.map { Uri.parse(it.contentUri) }))
    }
    LaunchedEffect(viewModel) {
        viewModel.outcomes.collect { snackbar.showSnackbar(outcomeMessage(resources, it)) }
    }
    when (val p = pending) {
        is Pending.Folder -> FolderDialog(
            title = stringResource(if (p.operation == TimelineViewModel.Operation.MOVE) R.string.timeline_move_to else R.string.timeline_copy_to),
            folders = folders,
            onPick = { folder ->
                pending = null
                if (p.operation == TimelineViewModel.Operation.MOVE) {
                    withWriteGrant(p.items) { viewModel.move(p.items, folder) }
                } else {
                    viewModel.copy(p.items, folder)
                }
            },
            onDismiss = { pending = null },
        )
        is Pending.Tag -> TagDialog(
            tags = tags,
            onAdd = { name ->
                pending = null
                viewModel.addTag(p.items, name)
            },
            onDismiss = { pending = null },
        )
        is Pending.Album -> AlbumPickerDialog(
            albums = albums,
            onPick = { id ->
                pending = null
                viewModel.addToAlbum(p.items, id)
            },
            onCreate = { title ->
                pending = null
                viewModel.addToAlbum(p.items, null, title)
            },
            onDismiss = { pending = null },
        )
        is Pending.Rename -> RenameDialog(
            currentName = p.name,
            onRename = { base ->
                pending = null
                withWriteGrant(listOf(p.item)) { viewModel.rename(p.item, base) }
            },
            onDismiss = { pending = null },
        )
        null -> Unit
    }
    BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }
    TimelineContent(
        access = access,
        cells = cells,
        onRequestAccess = { request.launch(MEDIA_PERMISSIONS) },
        onOpenSystemSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        },
        onOpenMedia = onOpenMedia,
        onOpenSettings = onOpenSettings,
        selection = selection,
        onToggle = viewModel::toggle,
        onSelectRange = viewModel::setSelection,
        onClearSelection = viewModel::clearSelection,
        onShare = { items -> context.startActivity(shareIntent(items)) },
        onTrash = { items ->
            trashed = items.map { Uri.parse(it.contentUri) }
            trash.launch(trashRequest(context, trashed, trashed = true))
        },
        onFavorite = { viewModel.setFavorite(it, true) },
        onTag = { pending = Pending.Tag(it) },
        onAlbum = { pending = Pending.Album(it) },
        onMove = { items ->
            viewModel.loadFolders()
            pending = Pending.Folder(TimelineViewModel.Operation.MOVE, items)
        },
        onCopy = { items ->
            viewModel.loadFolders()
            pending = Pending.Folder(TimelineViewModel.Operation.COPY, items)
        },
        onRename = { item ->
            scope.launch {
                val name = viewModel.currentName(item)
                if (name == null) {
                    snackbar.showSnackbar(notFoundMessage)
                } else {
                    pending = Pending.Rename(item, name)
                }
            }
        },
        snackbar = snackbar,
        query = query,
        onQueryChange = viewModel::setQuery,
        scrollToTop = viewModel.scrollToTop,
    )
}

/** A move, copy or rename waiting for the user's choice in a dialog. */
private sealed interface Pending {
    data class Folder(val operation: TimelineViewModel.Operation, val items: List<TimelineItem>) : Pending

    data class Rename(val item: TimelineItem, val name: String) : Pending

    data class Tag(val items: List<TimelineItem>) : Pending

    data class Album(val items: List<TimelineItem>) : Pending
}

private fun outcomeMessage(resources: Resources, outcome: TimelineViewModel.Outcome): String =
    when (outcome.result) {
        FileOperationResult.DONE -> when (outcome.operation) {
            TimelineViewModel.Operation.MOVE -> resources.getQuantityString(R.plurals.timeline_moved, outcome.count, outcome.count)
            TimelineViewModel.Operation.COPY -> resources.getQuantityString(R.plurals.timeline_copied, outcome.count, outcome.count)
            TimelineViewModel.Operation.RENAME -> resources.getString(R.string.timeline_renamed)
        }
        FileOperationResult.NOT_FOUND -> resources.getString(R.string.timeline_not_found)
        FileOperationResult.NAME_TAKEN -> resources.getString(R.string.timeline_name_taken)
        FileOperationResult.FAILED -> resources.getString(R.string.timeline_operation_failed)
    }

/** The system dialog that lets this app change files other apps own (GAL-005). */
private fun writeRequest(context: Context, uris: List<Uri>): IntentSenderRequest =
    IntentSenderRequest.Builder(MediaStore.createWriteRequest(context.contentResolver, uris).intentSender).build()

/** Hands the selected files to another app with read access only. */
internal fun shareIntent(items: List<TimelineItem>): Intent {
    val uris = ArrayList(items.map { Uri.parse(it.contentUri) })
    val type = when {
        items.all { it.kind == MediaKind.IMAGE } -> "image/*"
        items.all { it.kind == MediaKind.VIDEO } -> "video/*"
        else -> "*/*"
    }
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        setType(type)
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null)
}

/** The system dialog that moves files to, or back from, the trash (GAL-005). */
internal fun trashRequest(context: Context, uris: List<Uri>, trashed: Boolean): IntentSenderRequest =
    IntentSenderRequest.Builder(MediaStore.createTrashRequest(context.contentResolver, uris, trashed).intentSender).build()

@Composable
internal fun TimelineContent(
    access: MediaLibraryAccess,
    cells: LazyPagingItems<TimelineCell>,
    onRequestAccess: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenMedia: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    selection: Map<String, TimelineItem> = emptyMap(),
    onToggle: (TimelineItem) -> Unit = {},
    onSelectRange: (Map<String, TimelineItem>) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onShare: (List<TimelineItem>) -> Unit = {},
    onTrash: (List<TimelineItem>) -> Unit = {},
    onMove: (List<TimelineItem>) -> Unit = {},
    onCopy: (List<TimelineItem>) -> Unit = {},
    onRename: (TimelineItem) -> Unit = {},
    onFavorite: (List<TimelineItem>) -> Unit = {},
    onTag: (List<TimelineItem>) -> Unit = {},
    onAlbum: (List<TimelineItem>) -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    query: TimelineQuery = TimelineQuery(),
    onQueryChange: (TimelineQuery) -> Unit = {},
    scrollToTop: Flow<Unit> = emptyFlow(),
) {
    var sorting by rememberSaveable { mutableStateOf(false) }
    if (sorting) SortSheet(query, onQueryChange, onDismiss = { sorting = false })
    BoxWithConstraints {
        // Column ranges per width class (DESIGN §4.1): Compact 3–5, Medium 5–7, Expanded 6–10.
        val range = when {
            maxWidth < 600.dp -> 3..5
            maxWidth < 840.dp -> 5..7
            else -> 6..10
        }
        var chosen by rememberSaveable { mutableIntStateOf(range.first + 1) }
        val columns = chosen.coerceIn(range)
        val noAccess = access.images == LibraryAccess.NONE && access.videos == LibraryAccess.NONE
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (selection.isNotEmpty()) {
                    TopAppBar(
                        title = { Text(pluralStringResource(R.plurals.timeline_selected, selection.size, selection.size)) },
                        navigationIcon = {
                            IconButton(onClick = onClearSelection) {
                                Icon(painterResource(IrohacoIcons.Close), stringResource(R.string.timeline_clear_selection))
                            }
                        },
                    )
                    return@Scaffold
                }
                TopAppBar(
                    title = { Text(stringResource(R.string.timeline_title)) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(painterResource(IrohacoIcons.Settings), stringResource(R.string.timeline_settings))
                        }
                        if (!noAccess) {
                            IconButton(onClick = { sorting = true }) {
                                Icon(painterResource(IrohacoIcons.Sort), stringResource(R.string.timeline_sort))
                            }
                            IconButton(onClick = { chosen = (columns - 1).coerceIn(range) }, enabled = columns > range.first) {
                                Icon(painterResource(IrohacoIcons.ZoomIn), stringResource(R.string.timeline_columns_fewer))
                            }
                            IconButton(onClick = { chosen = (columns + 1).coerceIn(range) }, enabled = columns < range.last) {
                                Icon(painterResource(IrohacoIcons.ZoomOut), stringResource(R.string.timeline_columns_more))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            when {
                noAccess -> PermissionPrompt(padding, onRequestAccess, onOpenSystemSettings)
                else -> Column(Modifier.padding(top = padding.calculateTopPadding())) {
                    if (access.images == LibraryAccess.SELECTED || access.videos == LibraryAccess.SELECTED) {
                        PartialAccessBar(onRequestAccess)
                    }
                    TimelineGrid(
                        cells = cells,
                        columns = columns,
                        bottomPadding = padding.calculateBottomPadding(),
                        onFewerColumns = { chosen = (columns - 1).coerceIn(range) },
                        onMoreColumns = { chosen = (columns + 1).coerceIn(range) },
                        onOpenMedia = onOpenMedia,
                        selection = selection,
                        onToggle = onToggle,
                        onSelectRange = onSelectRange,
                        onShare = onShare,
                        onTrash = onTrash,
                        onMove = onMove,
                        onCopy = onCopy,
                        onRename = onRename,
                        onFavorite = onFavorite,
                        onTag = onTag,
                        onAlbum = onAlbum,
                        scrollToTop = scrollToTop,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineGrid(
    cells: LazyPagingItems<TimelineCell>,
    columns: Int,
    bottomPadding: Dp,
    onFewerColumns: () -> Unit,
    onMoreColumns: () -> Unit,
    onOpenMedia: (String) -> Unit,
    selection: Map<String, TimelineItem>,
    onToggle: (TimelineItem) -> Unit,
    onSelectRange: (Map<String, TimelineItem>) -> Unit,
    onShare: (List<TimelineItem>) -> Unit,
    onTrash: (List<TimelineItem>) -> Unit,
    onMove: (List<TimelineItem>) -> Unit,
    onCopy: (List<TimelineItem>) -> Unit,
    onRename: (TimelineItem) -> Unit,
    onFavorite: (List<TimelineItem>) -> Unit,
    onTag: (List<TimelineItem>) -> Unit,
    onAlbum: (List<TimelineItem>) -> Unit,
    scrollToTop: Flow<Unit>,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(scrollToTop) { scrollToTop.collect { gridState.scrollToItem(0) } }
    val currentSelection by rememberUpdatedState(selection)
    val selectRange by rememberUpdatedState(onSelectRange)
    val formatter = rememberDateFormatter()
    // The pinch detector is installed once, so it must call the latest callbacks.
    val fewer by rememberUpdatedState(onFewerColumns)
    val more by rememberUpdatedState(onMoreColumns)
    if (cells.itemCount == 0 && cells.loadState.refresh is LoadState.NotLoading) {
        Text(
            stringResource(R.string.timeline_empty),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(IrohacoSpacing.xxl),
        )
        return
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs),
            modifier = Modifier
                .fillMaxSize()
                .pinchToChangeColumns({ fewer() }, { more() })
                .dragToSelect(gridState, cells, { currentSelection }) { selectRange(it) },
        ) {
            items(
                count = cells.itemCount,
                key = cells.itemKey { if (it is TimelineCell.Header) "date-${it.date}" else (it as TimelineCell.Media).item.assetId },
                contentType = cells.itemContentType { it::class },
                span = { index -> GridItemSpan(if (cells.peek(index) is TimelineCell.Header) maxLineSpan else 1) },
            ) { index ->
                when (val cell = cells[index]) {
                    is TimelineCell.Header -> DateHeader(cell.date, formatter)
                    is TimelineCell.Media -> {
                        val date = formatter.format(cell.date)
                        val description = if (cell.item.kind == MediaKind.VIDEO) {
                            stringResource(R.string.timeline_video_description, date)
                        } else {
                            stringResource(R.string.timeline_photo_description, date)
                        }
                        val selecting = selection.isNotEmpty()
                        val selectLabel = stringResource(R.string.timeline_select)
                        MediaThumbnail(
                            model = cell.item.contentUri,
                            contentDescription = description,
                            selected = cell.item.assetId in selection,
                            modifier = Modifier
                                .sharedMedia(cell.item.assetId)
                                .clickable { if (selecting) onToggle(cell.item) else onOpenMedia(cell.item.assetId) }
                                // The long press itself is the grid's gesture; screen readers get it as an action.
                                .semantics { onLongClick(selectLabel) { onToggle(cell.item); true } },
                        )
                    }
                    null -> MediaThumbnail(model = null, contentDescription = "", selected = false)
                }
            }
        }
        // The date of the first visible row stays at the top while scrolling (DESIGN §4.1).
        val pinned by remember(cells) {
            derivedStateOf {
                // The list is empty until the first page arrives.
                val index = gridState.firstVisibleItemIndex
                when (val cell = if (index < cells.itemCount) cells.peek(index) else null) {
                    is TimelineCell.Header -> cell.date
                    is TimelineCell.Media -> cell.date
                    null -> null
                }
            }
        }
        pinned?.let { DateHeader(it, formatter) }
        if (selection.isNotEmpty()) {
            HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + IrohacoSpacing.l),
            ) {
                IconButton(onClick = { onShare(selection.values.toList()) }) {
                    Icon(painterResource(IrohacoIcons.Share), stringResource(R.string.timeline_share))
                }
                IconButton(onClick = { onFavorite(selection.values.toList()) }) {
                    Icon(painterResource(IrohacoIcons.Favorite), stringResource(R.string.timeline_favorite))
                }
                IconButton(onClick = { onTag(selection.values.toList()) }) {
                    Icon(painterResource(IrohacoIcons.NewLabel), stringResource(R.string.tags_add))
                }
                IconButton(onClick = { onAlbum(selection.values.toList()) }) {
                    Icon(painterResource(IrohacoIcons.LibraryAdd), stringResource(R.string.albums_add_to))
                }
                IconButton(onClick = { onTrash(selection.values.toList()) }) {
                    Icon(painterResource(IrohacoIcons.Delete), stringResource(R.string.timeline_delete))
                }
                // File operations are less frequent, so they wait behind "more" and keep the toolbar narrow.
                Box {
                    var more by remember { mutableStateOf(false) }
                    IconButton(onClick = { more = true }) {
                        Icon(painterResource(IrohacoIcons.MoreVert), stringResource(R.string.timeline_more))
                    }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.timeline_move)) },
                            leadingIcon = { Icon(painterResource(IrohacoIcons.Move), contentDescription = null) },
                            onClick = {
                                more = false
                                onMove(selection.values.toList())
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.timeline_copy)) },
                            leadingIcon = { Icon(painterResource(IrohacoIcons.Copy), contentDescription = null) },
                            onClick = {
                                more = false
                                onCopy(selection.values.toList())
                            },
                        )
                        if (selection.size == 1) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.timeline_rename)) },
                                leadingIcon = { Icon(painterResource(IrohacoIcons.Edit), contentDescription = null) },
                                onClick = {
                                    more = false
                                    onRename(selection.values.first())
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Long press, then drag: selects every cell between the pressed one and the finger (DESIGN §4.1).
 * A long press without moving selects just that cell. Taps stay with the cells.
 * shortcut: no auto-scroll at the grid edges yet; add it if selecting long ranges feels cramped on devices.
 */
private fun Modifier.dragToSelect(
    gridState: LazyGridState,
    cells: LazyPagingItems<TimelineCell>,
    selection: () -> Map<String, TimelineItem>,
    onSelect: (Map<String, TimelineItem>) -> Unit,
): Modifier = pointerInput(cells) {
    fun indexAt(position: Offset): Int? = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        position.x >= item.offset.x && position.x < item.offset.x + item.size.width &&
            position.y >= item.offset.y && position.y < item.offset.y + item.size.height
    }?.index
    val cellAt = { index: Int -> if (index < cells.itemCount) cells.peek(index) else null }
    // The Initial pass sees the touch before the cells' click handlers, so their consumption cannot cancel the press.
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val anchor = indexAt(down.position)?.takeIf { cellAt(it) is TimelineCell.Media } ?: return@awaitEachGesture
        // Lifting or moving past the slop before the timeout leaves the touch to taps and scrolling.
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        }
        if (released != null) return@awaitEachGesture
        val before = selection()
        onSelect(rangeSelection(before, cellAt, anchor, anchor))
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            change.consume() // once selecting, the grid neither scrolls nor opens the cell
            if (!change.pressed) break
            indexAt(change.position)?.let { onSelect(rangeSelection(before, cellAt, anchor, it)) }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate, formatter: DateTimeFormatter) {
    Text(
        text = formatter.format(date),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
    )
}

@Composable
internal fun rememberDateFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMdE"), locale) }
}

@Composable
private fun PartialAccessBar(onChange: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(start = IrohacoSpacing.l, end = IrohacoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.timeline_partial_access), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onChange) { Text(stringResource(R.string.timeline_partial_access_change)) }
        }
    }
}

@Composable
private fun PermissionPrompt(padding: PaddingValues, onRequest: () -> Unit, onOpenSystemSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(IrohacoSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.l, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.timeline_permission_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(stringResource(R.string.timeline_permission_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onRequest) { Text(stringResource(R.string.timeline_permission_grant)) }
        // After a permanent denial the system dialog no longer appears, so settings is the way back.
        TextButton(onClick = onOpenSystemSettings) { Text(stringResource(R.string.timeline_permission_settings)) }
    }
}

/** Two-finger pinch: spreading shows fewer, larger thumbnails; pinching in shows more. One finger still scrolls. */
private fun Modifier.pinchToChangeColumns(onFewer: () -> Unit, onMore: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zoom = 1f
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    if (zoom > PINCH_STEP) {
                        onFewer()
                        zoom = 1f
                    } else if (zoom < 1 / PINCH_STEP) {
                        onMore()
                        zoom = 1f
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    },
)

private const val PINCH_STEP = 1.3f

private val previewItems = (0 until 9).map { i ->
    TimelineItem("a$i", if (i % 4 == 0) MediaKind.VIDEO else MediaKind.IMAGE, "content://preview/$i", LocalDate.of(2026, 10, if (i < 4) 20 else 18).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
}

@IrohacoPreviews
@Composable
private fun TimelinePreview() {
    IrohacoTheme(dynamicColor = false) {
        val cells = flowOf(PagingData.from(previewItems).withDateHeaders(ZoneOffset.UTC)).collectAsLazyPagingItems()
        TimelineContent(MediaLibraryAccess(LibraryAccess.SELECTED, LibraryAccess.FULL), cells, {}, {})
    }
}

@IrohacoPreviews
@Composable
private fun TimelinePermissionPreview() {
    IrohacoTheme(dynamicColor = false) {
        val cells = flowOf(PagingData.empty<TimelineCell>()).collectAsLazyPagingItems()
        TimelineContent(MediaLibraryAccess(LibraryAccess.NONE, LibraryAccess.NONE), cells, {}, {})
    }
}

@IrohacoPreviews
@Composable
private fun TimelineSelectionPreview() {
    IrohacoTheme(dynamicColor = false) {
        val cells = flowOf(PagingData.from(previewItems).withDateHeaders(ZoneOffset.UTC)).collectAsLazyPagingItems()
        TimelineContent(
            MediaLibraryAccess(LibraryAccess.FULL, LibraryAccess.FULL), cells, {}, {},
            selection = previewItems.take(2).associateBy { it.assetId },
        )
    }
}
