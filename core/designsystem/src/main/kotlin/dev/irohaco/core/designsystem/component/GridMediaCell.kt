package dev.irohaco.core.designsystem.component

import android.content.Context
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.motion.rememberReducedMotion
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What every cell of a grid shares: the loader, the marks' icons and text, colors and sizes. Made once per
 * grid, so a cell composes no icons or text of its own.
 */
class GridCellStyle internal constructor(
    internal val context: Context,
    internal val loader: ImageLoader,
    internal val placeholder: Color,
    internal val checkBackground: Color,
    internal val checkColor: Color,
    internal val text: TextMeasurer,
    internal val labelStyle: TextStyle,
    internal val check: Painter,
    internal val pin: Painter,
    /** The play mark of a video. */
    val play: Painter,
    internal val corner: Float,
    internal val selectedCorner: Float,
    internal val inset: Float,
    internal val icon: Float,
    internal val checkSize: Float,
    internal val pinPadding: Float,
    internal val selectSpec: AnimationSpec<Float>?,
)

@Composable
fun rememberGridCellStyle(): GridCellStyle {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    // One entry per distinct video length on screen, so marks are not measured again on each draw.
    val text = rememberTextMeasurer(cacheSize = 64)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White)
    val check = painterResource(IrohacoIcons.CheckCircleFilled)
    val pin = painterResource(IrohacoIcons.LocationOn)
    val play = painterResource(IrohacoIcons.PlayArrowFilled)
    val spec = if (rememberReducedMotion()) null else MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    return remember(context, density, colors, shapes, text, labelStyle, check, pin, play, spec) {
        val size = Size(100f, 100f)
        with(density) {
            GridCellStyle(
                context = context,
                loader = SingletonImageLoader.get(context),
                placeholder = colors.surfaceContainerHigh,
                checkBackground = colors.surface,
                checkColor = colors.primary,
                text = text,
                labelStyle = labelStyle,
                check = check,
                pin = pin,
                play = play,
                corner = shapes.extraSmall.topStart.toPx(size, density),
                selectedCorner = shapes.medium.topStart.toPx(size, density),
                inset = IrohacoSpacing.xs.toPx(),
                icon = 14.dp.toPx(),
                checkSize = 24.dp.toPx(),
                pinPadding = 2.dp.toPx(),
                selectSpec = spec,
            )
        }
    }
}

/**
 * A square grid cell drawn by one node: the image, the corner marks and the selection (DESIGN.md §2.3), the same
 * look as [MediaThumbnail]. A grid composes, measures and lays out one node per cell instead of a dozen, which
 * keeps new rows cheap while scrolling and when a pinch shows hundreds of cells at once. Clicks, long presses
 * and what TalkBack reads belong to the caller's [modifier].
 *
 * @param contentUri the file; null shows only the placeholder.
 * @param dense a cell in an overview of many columns: no corner morph, no scale and no marks.
 */
@Composable
fun GridMediaCell(
    contentUri: String?,
    selected: Boolean,
    style: GridCellStyle,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelIcon: Painter? = null,
    located: Boolean = false,
    dense: Boolean = false,
) {
    Spacer(modifier.aspectRatio(1f).then(GridCellElement(contentUri, selected, style, label, labelIcon, located, dense)))
}

private data class GridCellElement(
    val uri: String?,
    val selected: Boolean,
    val style: GridCellStyle,
    val label: String?,
    val labelIcon: Painter?,
    val located: Boolean,
    val dense: Boolean,
) : ModifierNodeElement<GridCellNode>() {
    override fun create() = GridCellNode(uri, selected, style, label, labelIcon, located, dense)
    override fun update(node: GridCellNode) = node.update(uri, selected, style, label, labelIcon, located, dense)
}

private class GridCellNode(
    var uri: String?,
    var selected: Boolean,
    var style: GridCellStyle,
    var label: String?,
    var labelIcon: Painter?,
    var located: Boolean,
    var dense: Boolean,
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {
    private var image: ImageBitmap? = null
    private var key: String? = null
    private var px = 0
    private var load: Job? = null
    private var progress = if (selected) 1f else 0f
    private var morph: Job? = null
    private val clip = Path()

    override val shouldAutoInvalidate = false

    fun update(uri: String?, selected: Boolean, style: GridCellStyle, label: String?, labelIcon: Painter?, located: Boolean, dense: Boolean) {
        val other = uri != this.uri
        this.uri = uri
        this.style = style
        this.label = label
        this.labelIcon = labelIcon
        this.located = located
        this.dense = dense
        if (other) {
            // A reused node now shows another file: nothing of the old one carries over.
            image = null
            key = null
            load?.cancel()
            morph?.cancel()
            progress = if (selected) 1f else 0f
            this.selected = selected
            startLoad()
        } else if (selected != this.selected) {
            this.selected = selected
            val target = if (selected) 1f else 0f
            val spec = style.selectSpec
            morph?.cancel()
            if (dense || spec == null) {
                progress = target
            } else {
                morph = coroutineScope.launch {
                    animate(progress, target, animationSpec = spec) { value, _ ->
                        progress = value
                        invalidateDraw()
                    }
                }
            }
        }
        invalidateDraw()
    }

    override fun onAttach() = startLoad()

    override fun onRemeasured(size: IntSize) {
        val step = GridThumbnail.stepPx(size.width)
        if (step == px) return
        px = step
        startLoad()
    }

    private fun startLoad() {
        val uri = uri ?: return
        if (px == 0 || !isAttached) return
        val key = GridThumbnail(uri).memoryKey + "@" + px
        if (key == this.key && (image != null || load?.isActive == true)) return
        this.key = key
        load?.cancel()
        // A cached copy draws in this frame, so scrolling back and changing the columns show no placeholders.
        style.loader.memoryCache?.get(MemoryCache.Key(key))?.let {
            show(it.image.toBitmap().asImageBitmap())
            return
        }
        // The old step's image stays until the new one arrives, so a column change does not flash.
        val request = ImageRequest.Builder(style.context).data(GridThumbnail(uri)).size(px).memoryCacheKey(key).build()
        load = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = style.loader.execute(request) as? SuccessResult ?: return@launch
            show(result.image.toBitmap().asImageBitmap())
        }
    }

    private fun show(bitmap: ImageBitmap) {
        image = bitmap
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val style = style
        val p = progress
        if (dense) {
            drawCell(style)
            return
        }
        val scale = lerp(1f, SELECTED_SCALE, p)
        val radius = lerp(style.corner, style.selectedCorner, p)
        scale(scale) {
            clip.reset()
            clip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
            clipPath(clip) { drawCell(style) }
        }
        drawMarks(style)
        if (p > 0f || selected) drawCheck(style)
    }

    private fun DrawScope.drawCell(style: GridCellStyle) {
        drawRect(style.placeholder)
        val image = image ?: return
        // Center crop to the square.
        val side = minOf(image.width, image.height)
        drawImage(
            image,
            srcOffset = androidx.compose.ui.unit.IntOffset((image.width - side) / 2, (image.height - side) / 2),
            srcSize = IntSize(side, side),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }

    private fun DrawScope.drawMarks(style: GridCellStyle) {
        val inset = style.inset
        val label = label
        val icon = labelIcon
        if (label != null || icon != null) {
            // White on a translucent black pill stays readable over both dark and bright photos.
            val measured = label?.let { style.text.measure(it, style.labelStyle, maxLines = 1, softWrap = false) }
            val iconWidth = if (icon != null) style.icon else 0f
            var textWidth = measured?.size?.width?.toFloat() ?: 0f
            val room = size.width - inset * 4 - iconWidth
            // In a narrow cell the length does not fit; the play mark stays alone instead of a cut-off time.
            if (textWidth > room) textWidth = 0f
            val height = maxOf(style.icon, measured?.size?.height?.toFloat() ?: 0f)
            val width = inset * 2 + iconWidth + textWidth
            if (textWidth > 0f || icon != null) {
                val left = size.width - inset - width
                drawRoundRect(OVERLAY, Offset(left, inset), Size(width, height), CornerRadius(4.dp.toPx()))
                var x = left + inset
                if (icon != null) {
                    translate(x, inset + (height - style.icon) / 2) {
                        with(icon) { draw(Size(style.icon, style.icon), colorFilter = WHITE) }
                    }
                    x += style.icon
                }
                if (textWidth > 0f && measured != null) drawText(measured, topLeft = Offset(x, inset + (height - measured.size.height) / 2))
            }
        }
        if (located) {
            val box = style.icon + style.pinPadding * 2
            val top = size.height - inset - box
            drawCircle(OVERLAY, box / 2, Offset(inset + box / 2, top + box / 2))
            translate(inset + style.pinPadding, top + style.pinPadding) {
                with(style.pin) { draw(Size(style.icon, style.icon), colorFilter = WHITE) }
            }
        }
    }

    private fun DrawScope.drawCheck(style: GridCellStyle) {
        val s = style.checkSize
        val inset = style.inset
        drawCircle(style.checkBackground, s / 2, Offset(inset + s / 2, inset + s / 2))
        translate(inset, inset) {
            with(style.check) { draw(Size(s, s), colorFilter = ColorFilter.tint(style.checkColor)) }
        }
    }

    private companion object {
        const val SELECTED_SCALE = 0.9f

        /** Behind white marks over a photo; see MediaThumbnail's OVERLAY_ALPHA for the contrast. */
        val OVERLAY = Color.Black.copy(alpha = 0.6f)
        val WHITE = ColorFilter.tint(Color.White)
    }
}
