package dev.irohaco.feature.timeline

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import dev.irohaco.core.contracts.DateGrouping
import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.core.contracts.Tag
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineLabel
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.designsystem.component.FastScrollbar
import dev.irohaco.core.designsystem.component.GridMediaCell
import dev.irohaco.core.designsystem.component.cachedThumbnail
import dev.irohaco.core.designsystem.component.drawGridThumbnail
import dev.irohaco.core.designsystem.component.rememberGridCellStyle
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.motion.rememberReducedMotion
import dev.irohaco.core.designsystem.motion.sharedMedia
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.LibraryAccess
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign

private val MEDIA_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    }

/** The Photos tab (PRD GAL-001, DESIGN §4.1). */
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel,
    onOpenMedia: (assetId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit = {},
    /** Opens the places from the location history for the picked photos (#213). */
    onPlace: (assetIds: List<String>) -> Unit = {},
) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    val cells = viewModel.cells.collectAsLazyPagingItems()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val grouping by viewModel.dateGrouping.collectAsStateWithLifecycle()
    val selectedGroups by viewModel.selectedGroups.collectAsStateWithLifecycle()
    val showPinchHint by viewModel.showPinchHint.collectAsStateWithLifecycle()
    val locationPin by viewModel.locationPin.collectAsStateWithLifecycle()
    val chosenColumns by viewModel.columns.collectAsStateWithLifecycle()
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
    val pinchHint = stringResource(R.string.timeline_pinch_hint)
    val hasAccess = access.images != LibraryAccess.NONE || access.videos != LibraryAccess.NONE
    LaunchedEffect(showPinchHint, hasAccess) {
        if (!showPinchHint || !hasAccess) return@LaunchedEffect
        // Marked first, so leaving the screen while it shows does not show it again.
        viewModel.pinchHintShown()
        snackbar.showSnackbar(pinchHint, withDismissAction = true)
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
        placeCounts = viewModel.placeCounts,
        onRequestAccess = { request.launch(MEDIA_PERMISSIONS) },
        onOpenSystemSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        },
        onOpenMedia = onOpenMedia,
        onOpenSettings = onOpenSettings,
        onOpenMap = onOpenMap,
        onPlace = { items ->
            viewModel.clearSelection()
            onPlace(items.map { it.assetId })
        },
        selection = selection,
        onToggle = viewModel::toggle,
        onSelectRange = viewModel::setSelection,
        onClearSelection = viewModel::clearSelection,
        onShare = rememberShareAction(viewModel.sharing) { context.startActivity(shareIntent(it)) },
        onTrash = { items ->
            trashed = items.map { Uri.parse(it.contentUri) }
            trash.launch(trashRequest(context, trashed, trashed = true))
        },
        onFavorite = viewModel::setFavorite,
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
        tags = tags,
        onQueryChange = viewModel::setQuery,
        scrollToTop = viewModel.scrollToTop,
        grouping = grouping,
        onGrouping = viewModel::setDateGrouping,
        locationPin = locationPin,
        selectedGroups = selectedGroups,
        onToggleGroup = viewModel::toggleGroup,
        labelAt = viewModel::labelAt,
        layout = layout,
        chosenColumns = chosenColumns,
        onColumns = viewModel::setColumns,
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
internal fun writeRequest(context: Context, uris: List<Uri>): IntentSenderRequest =
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
    onOpenMap: () -> Unit = {},
    selection: Map<String, TimelineItem> = emptyMap(),
    onToggle: (TimelineItem) -> Unit = {},
    onSelectRange: (Map<String, TimelineItem>) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onShare: (List<TimelineItem>) -> Unit = {},
    onTrash: (List<TimelineItem>) -> Unit = {},
    onMove: (List<TimelineItem>) -> Unit = {},
    onCopy: (List<TimelineItem>) -> Unit = {},
    onRename: (TimelineItem) -> Unit = {},
    onPlace: (List<TimelineItem>) -> Unit = {},
    /** Adds the items to the favorites, or takes them out with false. */
    onFavorite: (List<TimelineItem>, Boolean) -> Unit = { _, _ -> },
    onTag: (List<TimelineItem>) -> Unit = {},
    onAlbum: (List<TimelineItem>) -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    query: TimelineQuery = TimelineQuery(),
    onQueryChange: (TimelineQuery) -> Unit = {},
    placeCounts: suspend () -> Map<String, Int> = { emptyMap() },
    scrollToTop: Flow<Unit> = emptyFlow(),
    grouping: DateGrouping = DateGrouping.DAY,
    onGrouping: (DateGrouping) -> Unit = {},
    /** Pins the cells that have a place, unless the user turned it off in the settings. */
    locationPin: Boolean = true,
    selectedGroups: Set<LocalDate> = emptySet(),
    onToggleGroup: (LocalDate) -> Unit = {},
    labelAt: suspend (Int) -> TimelineLabel? = { null },
    /** Whether a cell not loaded yet is a header, so it takes a whole row before its page arrives. */
    layout: CellLayout? = null,
    /** The columns the user chose, kept in the settings; null shows the default. */
    chosenColumns: Int? = null,
    onColumns: (Int) -> Unit = {},
    /** Tags the sort sheet offers as filters. */
    tags: List<Tag> = emptyList(),
) {
    var sorting by rememberSaveable { mutableStateOf(false) }
    if (sorting) SortSheet(query, onQueryChange, onDismiss = { sorting = false }, grouping = grouping, onGrouping = onGrouping, tags = tags)
    BoxWithConstraints {
        // The column counts per width class (DESIGN §4.1), and the one to start with. Any count in between can be
        // chosen: a pinch glides through them and the slider stops on each.
        val (range, initial) = when {
            maxWidth < 600.dp -> 3..28 to 4
            maxWidth < 840.dp -> 5..40 to 6
            else -> 6..45 to 8
        }
        val columns = (chosenColumns ?: initial).coerceIn(range)
        val noAccess = access.images == LibraryAccess.NONE && access.videos == LibraryAccess.NONE
        // The bar slides away while scrolling down and comes back on any scroll up (DESIGN §3, M1-27).
        // The selection bar does not take part, so the count and the clear button stay in view.
        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
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
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(painterResource(IrohacoIcons.Settings), stringResource(R.string.timeline_settings))
                        }
                        if (!noAccess) {
                            IconButton(onClick = { sorting = true }) { Icon(painterResource(IrohacoIcons.Sort), stringResource(R.string.timeline_sort_title)) }
                            // Pinch changes the columns; the menu keeps a way without gestures, as for TalkBack (M1-30).
                            var menu by remember { mutableStateOf(false) }
                            IconButton(onClick = { menu = true }) {
                                Icon(painterResource(IrohacoIcons.MoreVert), stringResource(R.string.timeline_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.viewer_show_on_map)) },
                                    leadingIcon = { Icon(painterResource(IrohacoIcons.Map), null) },
                                    onClick = { menu = false; onOpenMap() },
                                )
                                ColumnsSlider(columns, range, onColumns)
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
                    // Kept while selecting too: hiding it would move the grid under the finger mid-drag.
                    FilterRow(query, tags, onQueryChange, placeCounts = placeCounts)
                    TimelineGrid(
                        cells = cells,
                        columns = columns,
                        bottomPadding = padding.calculateBottomPadding(),
                        columnsBy = { (columns + it).coerceIn(range) },
                        onColumns = onColumns,
                        onOpenMedia = onOpenMedia,
                        selection = selection,
                        onToggle = onToggle,
                        onSelectRange = onSelectRange,
                        onShare = onShare,
                        onTrash = onTrash,
                        onMove = onMove,
                        onCopy = onCopy,
                        onRename = onRename,
                        onPlace = onPlace,
                        onFavorite = onFavorite,
                        onTag = onTag,
                        onAlbum = onAlbum,
                        scrollToTop = scrollToTop,
                        grouping = grouping,
                        locationPin = locationPin,
                        selectedGroups = selectedGroups,
                        onToggleGroup = onToggleGroup,
                        labelAt = labelAt,
                        layout = layout,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class) // LazyLayoutCacheWindow
@Composable
private fun TimelineGrid(
    cells: LazyPagingItems<TimelineCell>,
    columns: Int,
    bottomPadding: Dp,
    /** The column count that many steps away, without changing to it. */
    columnsBy: (Int) -> Int,
    onColumns: (Int) -> Unit,
    onOpenMedia: (String) -> Unit,
    selection: Map<String, TimelineItem>,
    onToggle: (TimelineItem) -> Unit,
    onSelectRange: (Map<String, TimelineItem>) -> Unit,
    onShare: (List<TimelineItem>) -> Unit,
    onTrash: (List<TimelineItem>) -> Unit,
    onMove: (List<TimelineItem>) -> Unit,
    onCopy: (List<TimelineItem>) -> Unit,
    onRename: (TimelineItem) -> Unit,
    onPlace: (List<TimelineItem>) -> Unit,
    onFavorite: (List<TimelineItem>, Boolean) -> Unit,
    onTag: (List<TimelineItem>) -> Unit,
    onAlbum: (List<TimelineItem>) -> Unit,
    scrollToTop: Flow<Unit>,
    grouping: DateGrouping,
    locationPin: Boolean,
    selectedGroups: Set<LocalDate>,
    onToggleGroup: (LocalDate) -> Unit,
    labelAt: suspend (Int) -> TimelineLabel? = { null },
    layout: CellLayout? = null,
) {
    // Each item is a whole line, not a cell: a line of 28 cells is one item to compose, place and prefetch
    // instead of 28. The lines come from where the headers fall, known before any page loads.
    val cellLayout = layout ?: remember(cells.itemCount) { cells.peekedLayout() }
    val rows = remember(cellLayout, columns) { cellLayout.rows(columns) }
    // Cells ahead are composed, so their thumbnails are loading before they show (M1-34). A new state
    // per column count, at the same first cell: the cache window keeps prefetching lines of the old
    // count and reads past them when the columns change (an ArrayIndexOutOfBounds in Compose, #181).
    val last = remember { arrayOfNulls<Pair<LazyGridState, CellRows>>(1) }
    // The grid a pinch faded in, kept as it is when the pinch ends on its column count.
    val handoff = remember { arrayOfNulls<LazyGridState>(1) }
    val saver = listSaver<LazyGridState, Int>(
        save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
        restore = { LazyGridState(ColumnsCacheWindow(columns), it[0], it[1]) },
    )
    val gridState = rememberSaveable(columns, saver = saver) {
        handoff[0]?.also { handoff[0] = null } ?: run {
            // The line that holds the old first cell, so changing the columns keeps the place.
            val first = last[0]?.takeIf { it.second.count > 0 }?.let { (state, old) -> old.first(state.firstVisibleItemIndex.coerceAtMost(old.count - 1)) }
            LazyGridState(ColumnsCacheWindow(columns), first?.let(rows::rowOf) ?: 0)
        }
    }
    last[0] = gridState to rows
    val currentRows by rememberUpdatedState(rows)
    LaunchedEffect(scrollToTop, gridState) { scrollToTop.collect { gridState.scrollToItem(0) } }
    val currentSelection by rememberUpdatedState(selection)
    val selectRange by rememberUpdatedState(onSelectRange)
    val formatter = rememberDateFormatter()
    val headerFormatter = rememberDateFormatter(
        when (grouping) {
            DateGrouping.YEAR -> "y"
            DateGrouping.MONTH -> "yMMMM"
            DateGrouping.DAY -> "yMMMdE"
        },
    )
    // Digits only, so the bubble stays one short line while dragging (M1-28).
    val scrubFormatter = rememberDateFormatter(
        when (grouping) {
            DateGrouping.YEAR -> "y"
            DateGrouping.MONTH -> "yMM"
            DateGrouping.DAY -> SCRUB_DATE
        },
    )
    // While pinching, every photo glides through the column counts the fingers pass, growing or shrinking on
    // the way, so one pinch can cross several; the photo under the fingers stays under them. Lifting settles
    // on the nearest count the pinch has gone most of the way to, and shows the grid of that count.
    val currentColumns by rememberUpdatedState(columns)
    val currentLayout by rememberUpdatedState(cellLayout)
    val columnsAt by rememberUpdatedState(columnsBy)
    val changeColumns by rememberUpdatedState(onColumns)
    var pinch by remember { mutableStateOf<Pinch?>(null) }
    // Column steps from where the pinch started, in between while the photos glide: 1.5 is half way from one more to two more.
    var level by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pivot by remember { mutableStateOf(Offset.Zero) }
    var anchor by remember { mutableIntStateOf(0) }
    var anchorAt by remember { mutableStateOf(Offset.Zero) }
    var main by remember { mutableStateOf(Layer.NONE) }
    var shownColumns by remember { mutableStateOf<Int?>(null) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val settleSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val handOverSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // The gliding photos, fading out over the grid once they have stopped.
    var handingOver by remember { mutableStateOf(false) }
    var glideAlpha by remember { mutableFloatStateOf(1f) }
    if (cells.itemCount == 0 && cells.loadState.refresh is LoadState.NotLoading) {
        Text(
            stringResource(R.string.timeline_empty),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(IrohacoSpacing.xxl),
        )
        return
    }
    val cellStyle = rememberGridCellStyle()
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val selectLabel = stringResource(R.string.timeline_select)
    // Built once the grid stops: a screen reader reads what stays on screen, and lines scrolled past never get read.
    val readable = rememberScreenReader() && !gridState.isScrollInProgress
    val open by rememberUpdatedState(onOpenMedia)
    val toggle by rememberUpdatedState(onToggle)
    val openCell = { item: TimelineItem -> if (currentSelection.isEmpty()) open(item.assetId) else toggle(item) }
    val spacingPx = with(LocalDensity.current) { IrohacoSpacing.xxs.toPx() }
    val headerPx = with(LocalDensity.current) { HEADER_HEIGHT.toPx() }
    val headerText = rememberTextMeasurer()
    val headerStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val headerInset = with(LocalDensity.current) { IrohacoSpacing.l.toPx() }
    val headerBackground = MaterialTheme.colorScheme.surface

    /** Where [cell] starts in a grid of [columns] laid out by [state], or null while its line is not shown. */
    fun topLeft(state: LazyGridState, rows: CellRows, columns: Int, cell: Int): Offset? {
        val row = rows.rowOf(cell)
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == row } ?: return null
        return Offset(item.offset.x + (cell - rows.first(row)) * (item.size.width + spacingPx) / columns, item.offset.y.toFloat())
    }

    /** Where every cell sits at each column count the pinch can reach, worked out as the pinch reaches it. */
    fun startPinch(): Pinch {
        val start = currentColumns
        val viewport = gridState.layoutInfo.viewportSize
        val width = viewport.width.toFloat()
        val height = viewport.height.toFloat()
        // A header shown now gives the real height of one; the default only when none is.
        val header = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index < currentRows.count && currentLayout.isHeader(currentRows.first(it.index)) }
            ?.size?.height?.toFloat() ?: headerPx
        val layout = currentLayout
        val steps = columnsAt
        val rowsAt = HashMap<Int, CellRows>()
        fun rows(columns: Int) = rowsAt.getOrPut(columns) { if (columns == start) currentRows else layout.rows(columns) }
        // The photo under the fingers moves as a zoom about them would carry it.
        fun anchorY(columns: Int) = pivot.y + (anchorAt.y - pivot.y) * start / columns
        return Pinch(
            columnsAt = steps,
            placeAt = { columns -> layout.place(rows(columns), columns, width, spacingPx, header, anchor, anchorY(columns), -height, 2 * height) },
            stateAt = { columns ->
                // The grid starts at the line whose top is at or above the screen's, so the anchor's line sits where the photos stopped.
                val toRows = rows(columns)
                var row = toRows.rowOf(anchor)
                var top = anchorY(columns)
                while (row > 0 && top > 0) {
                    row--
                    top -= (if (layout.isHeader(toRows.first(row))) header else (width - (columns - 1) * spacingPx) / columns) + spacingPx
                }
                LazyGridState(ColumnsCacheWindow(columns), row, (-top).toInt().coerceAtLeast(0))
            },
            start = start,
        )
    }

    // At the first or last column count, the grid only gives a little.
    fun edgeLayer(zoom: Float) = Layer(zoom.coerceIn(1 / EDGE_GIVE, EDGE_GIVE), Offset.Zero, pivot, 1f)

    // The cell under a point: the line under it, then the column within the line.
    val cellAt: (Offset) -> Int? = remember(gridState, columns, spacingPx) { { position ->
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            position.x >= item.offset.x && position.x < item.offset.x + item.size.width &&
                position.y >= item.offset.y && position.y < item.offset.y + item.size.height
        }?.takeIf { it.index < currentRows.count }?.let { item ->
            val column = ((position.x - item.offset.x) / ((item.size.width + spacingPx) / columns)).toInt()
            (currentRows.first(item.index) + column).takeIf { it < currentRows.end(item.index) }
        }
    } }
    @Composable
    fun Cell(index: Int, dense: Boolean, modifier: Modifier) {
        when (val cell = if (index < cells.itemCount) cells[index] else null) {
            is TimelineCell.Header -> DateHeader(cell.date, headerFormatter, cell.date in selectedGroups) { onToggleGroup(cell.date) }
            is TimelineCell.Group -> GroupHeader(cell.group)
            is TimelineCell.Media -> {
                val item = cell.item
                val located = locationPin && item.hasLocation
                GridMediaCell(
                    contentUri = item.contentUri,
                    selected = item.assetId in selection,
                    style = cellStyle,
                    label = item.badge,
                    labelIcon = if (item.badgeIcon != null) cellStyle.play else null,
                    located = located,
                    dense = dense,
                    modifier = modifier
                        // A shared element per cell costs on every frame; a cell this small has nothing to grow from.
                        .then(if (dense) Modifier else Modifier.sharedMedia(item.assetId))
                        // Taps and long presses are the grid's gestures; screen readers get them as actions.
                        // The text is built here, only when a screen reader reads the cell.
                        .then(
                            if (!readable) Modifier else Modifier.clearAndSetSemantics {
                                val text = item.description(formatter.format(cell.date), resources, locale)
                                contentDescription = if (located) resources.getString(R.string.timeline_located_description, text) else text
                                this.selected = item.assetId in currentSelection
                                onClick { openCell(item); true }
                                onLongClick(selectLabel) { onToggle(item); true }
                            },
                        ),
                )
            }
            null -> GridMediaCell(contentUri = null, selected = false, style = cellStyle, dense = dense, modifier = modifier)
        }
    }
    @Composable
    fun Lines(rows: CellRows, columns: Int, state: LazyGridState, modifier: Modifier) {
        val dense = columns >= DENSE_COLUMNS
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            state = state,
            contentPadding = PaddingValues(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs),
            modifier = modifier.fillMaxSize(),
        ) {
            // Index keys: a line holds different cells at each column count, so no cell's key fits it.
            items(
                count = rows.count,
                contentType = { row -> if (cellLayout.isHeader(rows.first(row))) 0 else 1 },
            ) { row ->
                val first = rows.first(row)
                if (cellLayout.isHeader(first)) {
                    Cell(first, dense, Modifier)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
                        val end = rows.end(row)
                        for (index in first until end) Cell(index, dense, Modifier.weight(1f))
                        // The last line of a group keeps the cells' width.
                        repeat(columns - (end - first)) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Lines(
            rows,
            columns,
            gridState,
            Modifier
                // Keyed on the grid, so the handlers see the grid of the current column count.
                .pinchToChangeColumns(
                    key = gridState,
                    onStart = {
                        settle?.cancel()
                        handingOver = false
                        glideAlpha = 1f
                        level = 0f
                        zoom = 1f
                        main = Layer.NONE
                        // About the middle of the grid on screen, wherever the fingers are.
                        val center = gridState.layoutInfo.viewportSize.let { Offset(it.width / 2f, it.height / 2f) }
                        pivot = center
                        anchor = cellAt(center) ?: gridState.firstVisibleItemIndex.takeIf { it < currentRows.count }?.let(currentRows::first) ?: 0
                        anchorAt = topLeft(gridState, currentRows, currentColumns, anchor) ?: center
                        pinch = startPinch()
                    },
                    onScale = { total ->
                        zoom = total
                        val p = pinch ?: return@pinchToChangeColumns
                        level = pinchLevel(total, p.start, p.columnsAt)
                        // The gliding photos stand in for the grid; past the first or last count, the grid only gives a little.
                        main = if (level != 0f) Layer.NONE.copy(alpha = 0f) else edgeLayer(total)
                        shownColumns = p.columnsAt(settleLevel(level))
                    },
                    onEnd = {
                        shownColumns = null
                        val p = pinch ?: return@pinchToChangeColumns
                        settle = scope.launch {
                            if (level == 0f) {
                                animate(zoom, 1f, animationSpec = settleSpec) { value, _ -> main = edgeLayer(value) }
                            } else {
                                // Kept on screen through the end of the move, even when it goes back to where it started.
                                handingOver = true
                                val to = settleLevel(level)
                                animate(level, to.toFloat(), animationSpec = settleSpec) { value, _ -> level = value }
                                val columns = p.columnsAt(to)
                                if (columns != p.start) {
                                    handoff[0] = p.stateAt(columns)
                                    changeColumns(columns)
                                }
                            }
                            // The grid comes in under the photos where they stopped, with the new columns in the same frame;
                            // the photos then fade out over it, so its marks, corners and sharper images come in softly.
                            main = Layer.NONE
                            if (handingOver) animate(1f, 0f, animationSpec = handOverSpec) { value, _ -> glideAlpha = value }
                            pinch = null
                            handingOver = false
                            glideAlpha = 1f
                            level = 0f
                        }
                    },
                )
                .dragToSelect(cells, cellAt, { currentSelection }) { selectRange(it) }
                .tapCell(cells, cellAt) { openCell(it) }
                // Inside the gestures, so they keep reading unscaled positions.
                .graphicsLayer { apply(main) },
        )
        pinch?.takeIf { level != 0f || handingOver }?.let { p ->
            // The photos read from memory once per pinch; one not in memory shows its placeholder.
            val images = remember(p) { HashMap<Int, ImageBitmap?>() }
            Spacer(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .drawBehind {
                        // Between the column counts on either side of the level.
                        val below = floor(level).toInt()
                        val t = level - below
                        val now = p.placed(below)
                        val next = p.placed(below + 1)
                        val shown = Rect(0f, 0f, size.width, size.height)
                        val headers = ArrayList<Pair<Rect, String>>()
                        for (cell in now.keys + next.keys) {
                            val from = now[cell]
                            val to = next[cell]
                            val rect = when {
                                from != null && to != null -> lerp(from, to, t)
                                else -> from ?: to!!
                            }
                            if (!rect.overlaps(shown)) continue
                            // A cell on only one side fades with it.
                            val alpha = when {
                                from != null && to != null -> 1f
                                from != null -> 1 - t
                                else -> t
                            }.coerceIn(0f, 1f) * glideAlpha
                            when (val c = if (cell < cells.itemCount) cells.peek(cell) else null) {
                                is TimelineCell.Media -> {
                                    val image = images.getOrPut(cell) { cellStyle.cachedThumbnail(c.item.contentUri, rect.width.toInt()) }
                                    drawGridThumbnail(cellStyle, image, rect, alpha)
                                }
                                is TimelineCell.Header -> headers += rect to headerFormatter.format(c.date)
                                is TimelineCell.Group, null -> if (c == null && !currentLayout.isHeader(cell)) drawGridThumbnail(cellStyle, null, rect, alpha)
                            }
                        }
                        // After the photos, on the grid's background, so photos gliding past go under the dates.
                        for ((rect, label) in headers) {
                            drawRect(headerBackground, rect.topLeft, rect.size, alpha = glideAlpha)
                            val text = headerText.measure(label, headerStyle, maxLines = 1)
                            drawText(text, topLeft = Offset(rect.left + headerInset, rect.center.y - text.size.height / 2f), alpha = glideAlpha)
                        }
                    },
            )
        }
        // The date of the first visible row stays at the top while scrolling (DESIGN §4.1).
        val pinned by remember(cells, gridState, rows) {
            derivedStateOf {
                // The list is empty until the first page arrives.
                val index = gridState.firstVisibleItemIndex.takeIf { it < rows.count }?.let(rows::first) ?: Int.MAX_VALUE
                // A date, or the group of a name, size or rating sort (M1-36).
                when (val cell = if (index < cells.itemCount) cells.peek(index) else null) {
                    is TimelineCell.Header -> cell.date
                    is TimelineCell.Group -> cell.group
                    is TimelineCell.Media -> cell.group ?: cell.date.groupStart(grouping)
                    null -> null
                }
            }
        }
        // The next group's header pushes the pinned one up as it nears the top, so the two never stack (M1-31).
        var pinnedHeight by remember { mutableIntStateOf(0) }
        // Not while the photos glide: the moving dates show where each group goes.
        pinned?.takeIf { pinch == null }?.let {
            Box(
                Modifier
                    .onSizeChanged { size -> pinnedHeight = size.height }
                    .offset {
                        val first = gridState.firstVisibleItemIndex
                        val next = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                            item.index > first && item.index < currentRows.count && cellLayout.isHeader(currentRows.first(item.index))
                        }
                        IntOffset(0, pinnedHeaderOffset(next?.offset?.y, pinnedHeight))
                    },
            ) {
                when (it) {
                    is LocalDate -> DateHeader(it, headerFormatter, it in selectedGroups) { onToggleGroup(it) }
                    is SortGroup -> GroupHeader(it)
                }
            }
        }
        // The column count while pinching; it keeps its last number while fading out.
        val lastShown = remember { intArrayOf(columns) }
        shownColumns?.let { lastShown[0] = it }
        AnimatedVisibility(
            visible = shownColumns != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = IrohacoSpacing.l),
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    pluralStringResource(R.plurals.timeline_columns_count, lastShown[0], lastShown[0]),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
                )
            }
        }
        val context = LocalContext.current
        val zone = ZoneId.systemDefault()
        var dragIndex by remember { mutableStateOf<Int?>(null) }
        val scrubLabel by rememberScrubLabel(dragIndex) { row ->
            labelAt(currentRows.first(row.coerceIn(0, currentRows.count - 1)))?.format(scrubFormatter, zone, context)
        }
        FastScrollbar(
            gridState = gridState,
            itemCount = rows.count,
            label = scrubLabel,
            onDragIndex = { dragIndex = it },
            modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPadding),
        )
        if (selection.isNotEmpty()) {
            HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + IrohacoSpacing.l),
            ) {
                IconButton(onClick = { onShare(selection.values.toList()) }) {
                    Icon(painterResource(IrohacoIcons.Share), stringResource(R.string.timeline_share))
                }
                IconButton(onClick = { onFavorite(selection.values.toList(), true) }) {
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
                            text = { Text(stringResource(R.string.viewer_unfavorite)) },
                            leadingIcon = { Icon(painterResource(IrohacoIcons.Favorite), contentDescription = null) },
                            onClick = {
                                more = false
                                onFavorite(selection.values.toList(), false)
                            },
                        )
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.timeline_place)) },
                            leadingIcon = { Icon(painterResource(IrohacoIcons.LocationOn), contentDescription = null) },
                            onClick = {
                                more = false
                                onPlace(selection.values.toList())
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
    cells: LazyPagingItems<TimelineCell>,
    indexAt: (Offset) -> Int?,
    selection: () -> Map<String, TimelineItem>,
    onSelect: (Map<String, TimelineItem>) -> Unit,
): Modifier = pointerInput(cells, indexAt) {
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

/**
 * A tap on a photo or video cell. One detector for the whole grid instead of one per cell, which a new row
 * would otherwise build for each cell. Scrolling and a long press consume the touch, so they never count as taps.
 */
private fun Modifier.tapCell(
    cells: LazyPagingItems<TimelineCell>,
    indexAt: (Offset) -> Int?,
    onTap: (TimelineItem) -> Unit,
): Modifier = pointerInput(cells, indexAt) {
    detectTapGestures { position ->
        val index = indexAt(position) ?: return@detectTapGestures
        (cells.peek(index) as? TimelineCell.Media)?.let { onTap(it.item) }
    }
}

/** The header of a date group; its button selects the whole group, even outside selection mode (GAL-012). */
@Composable
private fun DateHeader(date: LocalDate, formatter: DateTimeFormatter, selected: Boolean, onToggle: () -> Unit) {
    val label = formatter.format(date)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = IrohacoSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onToggle) {
            Icon(
                painterResource(if (selected) IrohacoIcons.CheckCircleFilled else IrohacoIcons.CheckCircle),
                stringResource(if (selected) R.string.timeline_unselect_group else R.string.timeline_select_group, label),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The header of a name, size or rating group; whole-group selection stays with date groups (M1-36). */
@Composable
private fun GroupHeader(group: SortGroup) {
    Text(
        group.title(),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 48.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = IrohacoSpacing.l),
    )
}

@Composable
internal fun SortGroup.title(): String {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    return when (this) {
        is SortGroup.Initial -> letter.toString()
        is SortGroup.Stars -> if (count <= 0) stringResource(R.string.timeline_unrated) else "★".repeat(count)
        is SortGroup.Size -> when (band) {
            0 -> stringResource(R.string.timeline_size_at_least, size(SIZE_BANDS[0]))
            SIZE_BANDS.lastIndex -> stringResource(R.string.timeline_size_under, size(SIZE_BANDS[band - 1]))
            else -> stringResource(R.string.timeline_size_between, size(SIZE_BANDS[band]), size(SIZE_BANDS[band - 1]))
        }
        is SortGroup.Distance -> distanceBandTitle(band)
    }
}

@Composable
internal fun distanceBandTitle(band: Int): String = when {
    band < DISTANCE_BANDS.size -> stringResource(R.string.timeline_distance_within, "%,d".format(DISTANCE_BANDS[band]))
    band == DISTANCE_BANDS.size -> stringResource(R.string.timeline_distance_farther, "%,d".format(DISTANCE_BANDS.last()))
    else -> stringResource(R.string.timeline_distance_none)
}

@Composable
internal fun rememberDateFormatter(skeleton: String = "yMMMdE"): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, skeleton) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale) }
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

/**
 * Two-finger pinch: spreading shows fewer, larger thumbnails; pinching in shows more. One finger still scrolls.
 * [onStart] comes when the second finger lands, [onScale] gets the zoom so far, and [onEnd] comes when they lift.
 */
private fun Modifier.pinchToChangeColumns(
    key: Any,
    onStart: () -> Unit,
    onScale: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zoom = 1f
            var pinched = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    if (!pinched) onStart()
                    pinched = true
                    zoom *= event.calculateZoom()
                    onScale(zoom)
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (pinched) onEnd()
        }
    },
)

/**
 * A pinch that started at [start] columns: [columnsAt] gives the count that many steps away, [placeAt] where each
 * cell sits at a count, and [stateAt] the grid to show when the pinch ends there.
 */
private class Pinch(
    val start: Int,
    val columnsAt: (Int) -> Int,
    private val placeAt: (Int) -> Map<Int, Rect>,
    val stateAt: (Int) -> LazyGridState,
) {
    private val placedAt = HashMap<Int, Map<Int, Rect>>()

    /** Where each cell sits [steps] column steps from the start. */
    fun placed(steps: Int): Map<Int, Rect> = columnsAt(steps).let { columns -> placedAt.getOrPut(columns) { placeAt(columns) } }
}

/**
 * Column steps from [start] for a pinch of [zoom], fractional between two counts: the cells grow by the zoom, and
 * a count is reached when they are its width. Positive steps have more columns. Stops at the first or last count.
 */
internal fun pinchLevel(zoom: Float, start: Int, columnsAt: (Int) -> Int): Float {
    val wanted = ln(zoom)
    val way = if (wanted >= 0) -1 else 1
    var steps = 0
    var grown = 0f
    while (true) {
        val next = columnsAt(steps + way)
        if (next == columnsAt(steps)) return steps.toFloat()
        val nextGrown = ln(start.toFloat() / next)
        if (abs(wanted) <= abs(nextGrown)) return steps + way * (wanted - grown) / (nextGrown - grown)
        steps += way
        grown = nextGrown
    }
}

/** The count a pinch at [level] settles on: the next one out once the pinch is [COMMIT] of the way there. */
internal fun settleLevel(level: Float): Int {
    val whole = level.toInt()
    return whole + if (abs(level - whole) >= COMMIT) sign(level).toInt() else 0
}

/** A grid's scale about [pivot], then its move by [shift], and how opaque it is. */
private data class Layer(val scale: Float, val shift: Offset, val pivot: Offset, val alpha: Float) {
    companion object {
        val NONE = Layer(1f, Offset.Zero, Offset.Zero, 1f)
    }
}

private fun GraphicsLayerScope.apply(layer: Layer) {
    scaleX = layer.scale
    scaleY = layer.scale
    translationX = layer.shift.x
    translationY = layer.shift.y
    alpha = layer.alpha
    if (size.width > 0 && size.height > 0) transformOrigin = TransformOrigin(layer.pivot.x / size.width, layer.pivot.y / size.height)
}

/** Past this much of the way, lifting the fingers keeps the new column count. */
private const val COMMIT = 0.4f

/** How much the grid scales at the first or last column count. */
private const val EDGE_GIVE = 1.08f

/** A header line: the date's 48 dp button, or a group name's minimum height. */
private val HEADER_HEIGHT = 48.dp

/**
 * The number of columns on a slider, for choosing one without a pinch, as with TalkBack (M1-30). The grid follows
 * the thumb at once; the count the thumb shows is kept while it moves, so it never waits on the saved setting.
 */
@Composable
private fun ColumnsSlider(columns: Int, range: IntRange, onColumns: (Int) -> Unit) {
    var sliding by remember { mutableStateOf<Float?>(null) }
    val shown = sliding?.roundToInt() ?: columns
    val label = stringResource(R.string.timeline_columns)
    Column(Modifier.width(COLUMNS_SLIDER_WIDTH).padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s)) {
        Text(pluralStringResource(R.plurals.timeline_columns_count, shown, shown), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = sliding ?: columns.toFloat(),
            onValueChange = { value ->
                sliding = value
                if (value.roundToInt() != columns) onColumns(value.roundToInt())
            },
            onValueChangeFinished = { sliding = null },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private val COLUMNS_SLIDER_WIDTH = 280.dp

/** From this many columns a cell is an overview dot: it drops the marks, the morph and the shared element. */
private const val DENSE_COLUMNS = 10

/**
 * Two screens ahead and one behind at four columns, less as the columns grow: the cells in a screen
 * grow with the square of the columns, so a fixed number of screens would compose and load far more
 * cells in a dense grid.
 */
@OptIn(ExperimentalFoundationApi::class)
private class ColumnsCacheWindow(private val columns: Int) : LazyLayoutCacheWindow {
    override fun Density.calculateAheadWindow(viewport: Int): Int = viewport * 8 / columns.coerceAtLeast(4)

    override fun Density.calculateBehindWindow(viewport: Int): Int = viewport * 4 / columns.coerceAtLeast(4)
}

/** How far the pinned header moves up: as much as the next header overlaps it, never down. */
internal fun pinnedHeaderOffset(nextHeaderTop: Int?, pinnedHeight: Int): Int =
    if (nextHeaderTop == null) 0 else (nextHeaderTop - pinnedHeight).coerceAtMost(0)

/** The fast scrollbar's date: digits in the order of the device's language, such as 2026/08/05. */
internal const val SCRUB_DATE = "yMMdd"

private val previewItems = (0 until 9).map { i ->
    val video = i % 4 == 0
    TimelineItem(
        "a$i",
        if (video) MediaKind.VIDEO else MediaKind.IMAGE,
        "content://preview/$i",
        LocalDate.of(2026, 10, if (i < 4) 20 else 18).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        // 0:46, 1:01:19, and one whose length is unknown.
        durationMs = listOf(46_000L, 3_679_000L, null).getOrNull(i / 4)?.takeIf { video },
        hasLocation = i % 3 == 1,
    )
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

private val groupedPreviewItems = listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 9, 3), LocalDate.of(2025, 12, 24)).flatMapIndexed { g, day ->
    (0 until 4).map { i -> TimelineItem("g$g-$i", MediaKind.IMAGE, "content://preview/$g$i", day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()) }
}

@IrohacoPreviews
@Composable
private fun TimelineMonthsPreview() {
    IrohacoTheme(dynamicColor = false) {
        val cells = flowOf(PagingData.from(groupedPreviewItems).withDateHeaders(ZoneOffset.UTC, DateGrouping.MONTH)).collectAsLazyPagingItems()
        TimelineContent(
            MediaLibraryAccess(LibraryAccess.FULL, LibraryAccess.FULL), cells, {}, {},
            grouping = DateGrouping.MONTH,
            selection = groupedPreviewItems.take(4).associateBy { it.assetId },
            selectedGroups = setOf(LocalDate.of(2026, 10, 1)),
        )
    }
}

@IrohacoPreviews
@Composable
private fun TimelineYearsPreview() {
    IrohacoTheme(dynamicColor = false) {
        val cells = flowOf(PagingData.from(groupedPreviewItems).withDateHeaders(ZoneOffset.UTC, DateGrouping.YEAR)).collectAsLazyPagingItems()
        TimelineContent(MediaLibraryAccess(LibraryAccess.FULL, LibraryAccess.FULL), cells, {}, {}, grouping = DateGrouping.YEAR)
    }
}

/** Where the headers fall among the cells loaded so far, for a grid without the view model's layout, as in previews. */
private fun LazyPagingItems<TimelineCell>.peekedLayout(): CellLayout {
    val at = (0 until itemCount).filter { peek(it)?.isHeader == true }
    return CellLayout(at.toIntArray(), at.map { peek(it)!! }, itemCount - at.size)
}
