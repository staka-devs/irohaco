package dev.irohaco.feature.timeline.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.motion.sharedMedia
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.designsystem.tokens.IrohacoViewerColors
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import kotlinx.coroutines.flow.flowOf

/** The full-screen photo viewer (PRD GAL-003, DESIGN §4.6). */
@Composable
fun MediaViewerScreen(viewModel: ViewerViewModel, onBack: () -> Unit) {
    val start by viewModel.start.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    when (val s = start) {
        ViewerViewModel.Start.NotFound -> LaunchedEffect(Unit) { onBack() } // the asset left the timeline meanwhile
        is ViewerViewModel.Start.At -> MediaViewerContent(items, s.index, onBack)
        ViewerViewModel.Start.Loading -> Box(Modifier.fillMaxSize().background(IrohacoViewerColors.background))
    }
}

@Composable
internal fun MediaViewerContent(items: LazyPagingItems<TimelineItem>, startIndex: Int, onBack: () -> Unit) {
    var chrome by rememberSaveable { mutableStateOf(true) }
    BackHandler(onBack = onBack)
    SystemBarsVisible(chrome)
    Box(Modifier.fillMaxSize().background(IrohacoViewerColors.background)) {
        if (items.itemCount > 0) {
            val pager = rememberPagerState(initialPage = startIndex.coerceAtMost(items.itemCount - 1)) { items.itemCount }
            val zoom = remember { ZoomState() }
            LaunchedEffect(pager.settledPage) { zoom.reset() }
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoom.isZoomed,
                key = items.itemKey { it.assetId },
                beyondViewportPageCount = 1,
            ) { page ->
                MediaPage(
                    item = items[page],
                    zoom = if (page == pager.currentPage) zoom else null,
                    onTap = { chrome = !chrome },
                )
            }
        }
        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().background(IrohacoViewerColors.scrim).statusBarsPadding()) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back), tint = IrohacoViewerColors.content)
                }
            }
        }
    }
}

@Composable
private fun MediaPage(item: TimelineItem?, zoom: ZoomState?, onTap: () -> Unit) {
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
            Icon(
                painterResource(IrohacoIcons.PlayCircleFilled),
                contentDescription = null,
                tint = IrohacoViewerColors.content,
                modifier = Modifier.size(72.dp),
            )
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
