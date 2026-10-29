package dev.irohaco.feature.timeline.viewer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.style.TextAlign
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.MetadataField
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.MediaDetailsResult
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaFormat
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import coil3.gif.AnimatedImageDecoder
import android.provider.Settings
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.component.VideoPlayer
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.motion.sharedMedia
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.designsystem.tokens.IrohacoViewerColors
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.TagDialog
import dev.irohaco.feature.timeline.shareIntent
import dev.irohaco.feature.timeline.trashRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The full-screen photo viewer (PRD GAL-003, DESIGN §4.6). */
@Composable
fun MediaViewerScreen(viewModel: ViewerViewModel, onBack: () -> Unit, onFindSimilar: (String) -> Unit = {}) {
    val start by viewModel.start.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    var detailsOf by remember { mutableStateOf<TimelineItem?>(null) }
    val marks by viewModel.marks.collectAsStateWithLifecycle()
    val format by viewModel.format.collectAsStateWithLifecycle()
    val location by viewModel.location.collectAsStateWithLifecycle()
    val placeName by viewModel.placeName.collectAsStateWithLifecycle()
    HdrWindow(format.hdr)
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val memos by viewModel.memos.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val edits by viewModel.edits.collectAsStateWithLifecycle()
    var tagging by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MetadataField?>(null) }
    val fileDetails = ((details as? ViewerViewModel.Details.Shown)?.result as? MediaDetailsResult.Read)?.details
    detailsOf?.let { item ->
        val done = { editing = null }
        when (editing) {
            MetadataField.TAKEN -> TakenAtDialog(
                initialMs = edits.takenAtMs ?: item.sortTimeMs,
                onDismiss = done,
                onSave = { done(); viewModel.setTakenAt(item, it) },
            )
            MetadataField.LOCATION -> LocationDialog(
                initial = (location as? Detail.Value<GeoPoint>)?.value,
                onDismiss = done,
                onSave = { done(); viewModel.setLocation(item, it) },
            )
            MetadataField.DESCRIPTION -> DescriptionDialog(
                initial = when (val e = edits.description) {
                    is FieldEdit.Set -> e.value
                    FieldEdit.Removed -> ""
                    null -> (fileDetails?.description as? Detail.Value<String>)?.value.orEmpty()
                },
                onDismiss = done,
                onSave = { done(); viewModel.setDescription(item, it) },
            )
            null -> Unit
        }
    }
    if (tagging) {
        TagDialog(
            tags = allTags,
            onAdd = { name ->
                tagging = false
                detailsOf?.let { viewModel.addTag(it, name) }
            },
            onDismiss = { tagging = false },
        )
    }
    val allowLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        detailsOf?.let(viewModel::showDetails) // read again: GPS may now be readable
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var trashedUri by remember { mutableStateOf<Uri?>(null) }
    val trashedMessage = stringResource(R.string.viewer_trashed)
    val undoLabel = stringResource(R.string.timeline_undo)
    val sceneNotIndexed = stringResource(R.string.memo_scene_not_indexed)
    LaunchedEffect(viewModel) { viewModel.sceneNotIndexed.collect { snackbar.showSnackbar(sceneNotIndexed) } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = trashedUri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val answer = snackbar.showSnackbar(trashedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
            if (answer == SnackbarResult.ActionPerformed) restore.launch(trashRequest(context, listOf(uri), trashed = false))
        }
    }
    MediaDetailsSheet(
        state = details,
        kind = detailsOf?.kind ?: MediaKind.IMAGE,
        onDismiss = viewModel::hideDetails,
        onAllowLocation = { allowLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
        rating = marks.rating,
        onRate = { rating -> detailsOf?.let { viewModel.setRating(it, rating) } },
        tags = tags,
        onAddTag = { tagging = true },
        onRemoveTag = { tagId -> detailsOf?.let { viewModel.removeTag(it, tagId) } },
        memos = {
            MemoSection(
                memos = memos,
                onAdd = { kind, text, atScene -> detailsOf?.let { viewModel.addMemo(it, kind, text, atScene) } },
                onEdit = viewModel::editMemo,
                onSearchable = viewModel::setMemoSearchable,
                onDelete = viewModel::deleteMemo,
                history = viewModel::memoHistory,
                isVideo = detailsOf?.kind == MediaKind.VIDEO,
            )
        },
        edits = edits,
        onEdit = { editing = it },
        onRevert = { field ->
            detailsOf?.let {
                when (field) {
                    MetadataField.TAKEN -> viewModel.setTakenAt(it, null)
                    MetadataField.LOCATION -> viewModel.setLocation(it, null)
                    MetadataField.DESCRIPTION -> viewModel.setDescription(it, null)
                }
            }
        },
        onKeep = { field -> detailsOf?.let { viewModel.keepEdit(it, field) } },
    )
    Box(Modifier.fillMaxSize()) {
        when (val s = start) {
            ViewerViewModel.Start.NotFound -> LaunchedEffect(Unit) { onBack() } // the asset left the timeline meanwhile
            is ViewerViewModel.Start.At -> MediaViewerContent(
                items = items,
                startIndex = s.index,
                onBack = onBack,
                playing = playing,
                onPlay = viewModel::play,
                onPageChanged = { assetId -> if (playing != null && playing?.first != assetId) viewModel.stop() },
                onShowDetails = { item ->
                    detailsOf = item
                    viewModel.showDetails(item)
                },
                favorite = marks.favorite,
                onCurrentChanged = viewModel::showing,
                onToggleFavorite = { viewModel.setFavorite(it, !marks.favorite) },
                format = format,
                hasLocation = location is Detail.Value<GeoPoint>,
                placeName = placeName,
                onShare = { item -> context.startActivity(shareIntent(listOf(item))) },
                onFindSimilar = { onFindSimilar(it.assetId) },
                onShowOnMap = { (location as? Detail.Value<GeoPoint>)?.value?.let { context.startActivity(mapIntent(it)) } },
                onTrash = { item ->
                    trashedUri = Uri.parse(item.contentUri)
                    trash.launch(trashRequest(context, listOf(Uri.parse(item.contentUri)), trashed = true))
                },
            )
            ViewerViewModel.Start.Loading -> Box(Modifier.fillMaxSize().background(IrohacoViewerColors.background))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
internal fun MediaViewerContent(
    items: LazyPagingItems<TimelineItem>,
    startIndex: Int,
    onBack: () -> Unit,
    playing: Pair<String, Player>? = null,
    onPlay: (TimelineItem) -> Unit = {},
    onPageChanged: (assetId: String?) -> Unit = {},
    onShowDetails: (TimelineItem) -> Unit = {},
    favorite: Boolean = false,
    onCurrentChanged: (TimelineItem?) -> Unit = {},
    onToggleFavorite: (TimelineItem) -> Unit = {},
    format: MediaFormat = MediaFormat(),
    hasLocation: Boolean = false,
    placeName: String? = null,
    onShare: (TimelineItem) -> Unit = {},
    onFindSimilar: (TimelineItem) -> Unit = {},
    onShowOnMap: (TimelineItem) -> Unit = {},
    onTrash: (TimelineItem) -> Unit = {},
) {
    var current by remember { mutableStateOf<TimelineItem?>(null) }
    LaunchedEffect(current) { onCurrentChanged(current) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    BackHandler(onBack = onBack)
    SystemBarsVisible(chrome)
    Box(Modifier.fillMaxSize().background(IrohacoViewerColors.background)) {
        if (items.itemCount > 0) {
            val pager = rememberPagerState(initialPage = startIndex.coerceAtMost(items.itemCount - 1)) { items.itemCount }
            val zoom = remember { ZoomState() }
            LaunchedEffect(pager.currentPage, items.itemCount) { current = items.peek(pager.currentPage) }
            LaunchedEffect(pager.settledPage) {
                zoom.reset()
                onPageChanged(items.peek(pager.settledPage)?.assetId) // playback stays on the page it started on
            }
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoom.isZoomed,
                key = items.itemKey { it.assetId },
                beyondViewportPageCount = 1,
            ) { page ->
                val item = items[page]
                val player = playing?.takeIf { it.first == item?.assetId }?.second
                if (item != null && player != null) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }) }) {
                        val context = LocalContext.current
                        VideoPlayer(
                            player,
                            showControls = chrome,
                            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                            scrubFrame = { positionMs, frameModifier ->
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(item.contentUri).videoFrameMillis(positionMs).build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = frameModifier,
                                )
                            },
                        )
                    }
                } else {
                    MediaPage(
                        item = item,
                        // Videos are not zoomed; their page shows a frame and the play button.
                        zoom = if (page == pager.currentPage && item?.kind != MediaKind.VIDEO) zoom else null,
                        onTap = { chrome = !chrome },
                        onPlay = { item?.let(onPlay) },
                        // The probe looks at the page on screen only; its neighbours wait as stills.
                        animated = page == pager.currentPage && format.animated,
                    )
                }
            }
        }
        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().background(IrohacoViewerColors.scrim).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back), tint = IrohacoViewerColors.content)
                }
                val dateTimeFormatter = rememberDateTimeFormatter()
                Column(Modifier.weight(1f).padding(horizontal = IrohacoSpacing.s)) {
                    Text(
                        text = current?.let { dateTimeFormatter.format(Instant.ofEpochMilli(it.sortTimeMs).atZone(ZoneId.systemDefault())) } ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = IrohacoViewerColors.content,
                        maxLines = 1,
                    )
                    if (placeName != null) {
                        Text(placeName, style = MaterialTheme.typography.bodySmall, color = IrohacoViewerColors.content, maxLines = 1)
                    }
                }
                current?.let { item ->
                    IconButton(onClick = { onToggleFavorite(item) }) {
                        Icon(
                            painterResource(if (favorite) IrohacoIcons.FavoriteFilled else IrohacoIcons.Favorite),
                            stringResource(if (favorite) R.string.viewer_unfavorite else R.string.viewer_favorite),
                            tint = IrohacoViewerColors.content,
                        )
                    }
                    IconButton(onClick = { onShowDetails(item) }) {
                        Icon(painterResource(IrohacoIcons.Info), stringResource(R.string.details_open), tint = IrohacoViewerColors.content)
                    }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(IrohacoIcons.MoreVert), stringResource(R.string.timeline_more), tint = IrohacoViewerColors.content)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ViewerActionsMenu(
                                hasLocation = hasLocation,
                                onShare = { menu = false; onShare(item) },
                                onFindSimilar = { menu = false; onFindSimilar(item) },
                                onShowOnMap = { menu = false; onShowOnMap(item) },
                                onShowDetails = { menu = false; onShowDetails(item) },
                                onTrash = { menu = false; onTrash(item) },
                            )
                        }
                    }
                }
            }
            FormatLabels(format)
            }
        }
    }
}

/** The viewer's "more" menu (PRD GAL-013): share, the file's location, its details and the trash. */
@Composable
private fun ColumnScope.ViewerActionsMenu(
    hasLocation: Boolean,
    onShare: () -> Unit,
    onFindSimilar: () -> Unit,
    onShowOnMap: () -> Unit,
    onShowDetails: () -> Unit,
    onTrash: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.timeline_share)) },
        leadingIcon = { Icon(painterResource(IrohacoIcons.Share), contentDescription = null) },
        onClick = onShare,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.viewer_find_similar)) },
        leadingIcon = { Icon(painterResource(IrohacoIcons.Search), contentDescription = null) },
        onClick = onFindSimilar,
    )
    if (hasLocation) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.viewer_show_on_map)) },
            leadingIcon = { Icon(painterResource(IrohacoIcons.Map), contentDescription = null) },
            onClick = onShowOnMap,
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.details_open)) },
        leadingIcon = { Icon(painterResource(IrohacoIcons.Info), contentDescription = null) },
        onClick = onShowDetails,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.timeline_delete)) },
        leadingIcon = { Icon(painterResource(IrohacoIcons.Delete), contentDescription = null) },
        onClick = onTrash,
    )
}

@Composable
private fun MediaPage(item: TimelineItem?, zoom: ZoomState?, onTap: () -> Unit, onPlay: () -> Unit, animated: Boolean = false) {
    var size by remember { mutableStateOf(Size.Zero) }
    val gestures = if (zoom == null) {
        Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
    } else {
        Modifier
            .pointerInput(zoom) { detectZoom(zoom) }
            .pointerInput(zoom) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { zoom.toggle(it - size.center(), size) })
            }
    }
    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it.toSize() }.then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        if (item == null) return@Box
        val context = LocalContext.current
        val description = stringResource(
            if (item.kind == MediaKind.VIDEO) R.string.viewer_video_description else R.string.viewer_photo_description,
        )
        var failed by remember(item.contentUri) { mutableStateOf(false) }
        // GAL-016: plays on its own unless the device asks for less motion; then it waits for the button.
        val reduceMotion = remember { reducedMotion(context) }
        var playing by remember(item.contentUri, animated) { mutableStateOf(animated && !reduceMotion) }
        if (failed && item.kind == MediaKind.IMAGE) {
            // Better a reason than a blank page; the details sheet still opens from the bar.
            Text(
                stringResource(R.string.viewer_cannot_show),
                color = IrohacoViewerColors.content,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(IrohacoSpacing.xl),
            )
        }
        AsyncImage(
            onError = { failed = true },
            // Large enough to stay sharp when zoomed, bounded so huge photos do not exhaust memory.
            model = remember(item.contentUri, playing) {
                ImageRequest.Builder(context).data(item.contentUri).size(MAX_DECODE_PX)
                    // Frames are decoded one at a time by the platform, so a long animation does not pile up in memory.
                    .apply { if (playing) decoderFactory(AnimatedImageDecoder.Factory()) }
                    .build()
            },
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .sharedMedia(item.assetId)
                .graphicsLayer {
                    scaleX = zoom?.scale ?: 1f
                    scaleY = zoom?.scale ?: 1f
                    translationX = zoom?.offset?.x ?: 0f
                    translationY = zoom?.offset?.y ?: 0f
                },
        )
        if (animated && !playing) {
            IconButton(onClick = { playing = true }, modifier = Modifier.size(96.dp)) {
                Icon(
                    painterResource(IrohacoIcons.PlayCircleFilled),
                    contentDescription = stringResource(R.string.viewer_play_animation),
                    tint = IrohacoViewerColors.content,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        if (item.kind == MediaKind.VIDEO) {
            IconButton(onClick = onPlay, modifier = Modifier.size(96.dp)) {
                Icon(
                    painterResource(IrohacoIcons.PlayCircleFilled),
                    contentDescription = stringResource(R.string.viewer_play),
                    tint = IrohacoViewerColors.content,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
    }
}

/** Two fingers zoom; once zoomed, one finger pans. At fit, one finger is left to the pager's swipe. */
private suspend fun PointerInputScope.detectZoom(zoom: ZoomState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2 || zoom.isZoomed) {
                val bounds = size.toSize()
                zoom.transform(event.calculateZoom(), event.calculatePan(), event.calculateCentroid() - bounds.center(), bounds)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun Size.center() = Offset(width / 2f, height / 2f)

/** The device's "Remove animations" switch sets the animator scale to 0. */
private fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** The date and time shown in the viewer's bar (PRD GAL-013). */
@Composable
private fun rememberDateTimeFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMdEjm"), locale) }
}

/** Opens the device's map app at this point (PRD GAL-013); no app data about it is shown first. */
internal fun mapIntent(point: GeoPoint): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}"))

/** Hides the status and navigation bars while the overlays are hidden, and shows light icons on black. */
@Composable
private fun SystemBarsVisible(visible: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(visible) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val lightBefore = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightBefore
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val MAX_DECODE_PX = 4096

@IrohacoPreviews
@Composable
private fun MediaViewerPreview() {
    IrohacoTheme(dynamicColor = false) {
        val items = flowOf(
            PagingData.from(listOf(TimelineItem("v1", MediaKind.VIDEO, "content://preview/v1", 0))),
        ).collectAsLazyPagingItems()
        MediaViewerContent(items, startIndex = 0, onBack = {}, hasLocation = true, placeName = "千代田区, 東京都")
    }
}

@IrohacoPreviews
@Composable
private fun ViewerMenuPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Column { ViewerActionsMenu(hasLocation = true, onShare = {}, onFindSimilar = {}, onShowOnMap = {}, onShowDetails = {}, onTrash = {}) }
        }
    }
}

/** What the viewer can and cannot show of the current file (PRD GAL-009). */
@Composable
private fun FormatLabels(format: MediaFormat) {
    val labels = buildList {
        if (format.raw) add(stringResource(R.string.viewer_format_raw))
        if (format.hdr) add(stringResource(if (hdrDisplay()) R.string.viewer_format_hdr else R.string.viewer_format_hdr_sdr))
        if (format.motionPhoto) add(stringResource(R.string.viewer_format_motion_photo))
    }
    if (labels.isEmpty()) return
    Row(
        Modifier.padding(start = IrohacoSpacing.l, end = IrohacoSpacing.l, bottom = IrohacoSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
    ) {
        labels.forEach { label ->
            Surface(shape = MaterialTheme.shapes.small, color = IrohacoViewerColors.scrim, contentColor = IrohacoViewerColors.content) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = IrohacoSpacing.s, vertical = IrohacoSpacing.xs))
            }
        }
    }
}

/** Whether this screen can show HDR; before Android 14 the gain map is never used. */
@Composable
private fun hdrDisplay(): Boolean {
    val context = LocalContext.current
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && context.display.isHdrSdrRatioAvailable
}

/** Switches the window to HDR while an HDR photo is on screen, and back when it leaves. */
@Composable
private fun HdrWindow(hdr: Boolean) {
    val activity = LocalActivity.current ?: return
    val supported = hdrDisplay()
    DisposableEffect(hdr, supported) {
        if (hdr && supported) activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR
        onDispose { activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT }
    }
}
