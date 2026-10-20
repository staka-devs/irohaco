package dev.irohaco.feature.timeline.viewer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil3.request.ImageRequest
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
import kotlinx.coroutines.flow.flowOf

/** The full-screen photo viewer (PRD GAL-003, DESIGN §4.6). */
@Composable
fun MediaViewerScreen(viewModel: ViewerViewModel, onBack: () -> Unit) {
    val start by viewModel.start.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    var detailsOf by remember { mutableStateOf<TimelineItem?>(null) }
    val marks by viewModel.marks.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    var tagging by remember { mutableStateOf(false) }
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
    )
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
        )
        ViewerViewModel.Start.Loading -> Box(Modifier.fillMaxSize().background(IrohacoViewerColors.background))
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
                        VideoPlayer(player, showControls = chrome, modifier = Modifier.fillMaxSize().navigationBarsPadding())
                    }
                } else {
                    MediaPage(
                        item = item,
                        // Videos are not zoomed; their page shows a frame and the play button.
                        zoom = if (page == pager.currentPage && item?.kind != MediaKind.VIDEO) zoom else null,
                        onTap = { chrome = !chrome },
                        onPlay = { item?.let(onPlay) },
                    )
                }
            }
        }
        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().background(IrohacoViewerColors.scrim).statusBarsPadding()) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back), tint = IrohacoViewerColors.content)
                }
                Spacer(Modifier.weight(1f))
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
                }
            }
        }
    }
}

@Composable
private fun MediaPage(item: TimelineItem?, zoom: ZoomState?, onTap: () -> Unit, onPlay: () -> Unit) {
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
        AsyncImage(
            // Large enough to stay sharp when zoomed, bounded so huge photos do not exhaust memory.
            model = remember(item.contentUri) { ImageRequest.Builder(context).data(item.contentUri).size(MAX_DECODE_PX).build() },
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
        MediaViewerContent(items, startIndex = 0, onBack = {})
    }
}
