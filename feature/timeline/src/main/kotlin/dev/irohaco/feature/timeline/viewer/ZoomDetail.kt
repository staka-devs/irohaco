package dev.irohaco.feature.timeline.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Over a zoomed photo, the part on screen read again from the file at the screen's resolution once the
 * zoom stops moving; the screen-sized image under it only gets stretched. Formats the platform cannot
 * read by region (GIF, for one) keep the stretched image alone.
 */
@Composable
internal fun ZoomDetail(contentUri: String, zoom: ZoomState, container: Size) {
    val context = LocalContext.current
    val source by produceState<DetailSource?>(null, contentUri) {
        value = withContext(Dispatchers.IO) { DetailSource.open(context, contentUri) }
    }
    DisposableEffect(source) { onDispose { source?.close() } }
    var detail by remember(contentUri) { mutableStateOf<Detail?>(null) }
    LaunchedEffect(source, container) {
        val src = source ?: return@LaunchedEffect
        snapshotFlow { zoom.scale to zoom.offset }.collectLatest { (scale, offset) ->
            if (scale <= 1f) {
                detail = null
                return@collectLatest
            }
            delay(SETTLE_MS) // the last piece stays in place while the fingers move; reading waits until they stop
            val region = visibleRegion(src.image, container, scale, offset) ?: return@collectLatest
            val sample = sampleSize(src.image, container, scale)
            detail = withContext(Dispatchers.IO) { src.read(region, sample) }?.let { Detail(it.asImageBitmap(), region) }
        }
    }
    val shown = detail ?: return
    val src = source ?: return
    Canvas(Modifier.fillMaxSize()) {
        val on = screenRect(shown.region, src.image, size, zoom.scale, zoom.offset)
        drawImage(
            shown.bitmap,
            dstOffset = IntOffset(on.left.roundToInt(), on.top.roundToInt()),
            dstSize = IntSize(on.width.roundToInt(), on.height.roundToInt()),
        )
    }
}

private class Detail(val bitmap: ImageBitmap, val region: Rect)

private class DetailSource(private val decoder: BitmapRegionDecoder, private val degrees: Int) {
    private val raw = IntSize(decoder.width, decoder.height)

    /** A read may still be running on another thread when the screen leaves; the decoder is freed after it. */
    @Synchronized
    fun close() = decoder.recycle()

    /** The photo's size as it is shown, turned by its EXIF orientation. */
    val image = if (degrees % 180 == 0) raw else IntSize(raw.height, raw.width)

    @Synchronized
    fun read(region: Rect, sample: Int): Bitmap? {
        if (decoder.isRecycled) return null
        val r = rawRect(region, raw, degrees)
        val rect = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        if (rect.isEmpty) return null
        val bitmap = runCatching { decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
        if (bitmap == null || degrees == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    }

    companion object {
        fun open(context: Context, contentUri: String): DetailSource? = runCatching {
            val resolver = context.contentResolver
            val uri = contentUri.toUri()
            val orientation = resolver.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            // shortcut: mirrored orientations are read as unturned, so their detail is flipped; handle them if such photos turn up.
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            DetailSource(resolver.openInputStream(uri)!!.use { BitmapRegionDecoder.newInstance(it) }!!, degrees)
        }.getOrNull()
    }
}

/** The fit scale of [image] in [container], as ContentScale.Fit draws it. */
private fun fit(image: IntSize, container: Size) = min(container.width / image.width, container.height / image.height)

/**
 * The part of [image], fit in [container] and zoomed as [ZoomState] draws it, that is on screen, in the
 * photo's pixels; null when none of it is.
 */
internal fun visibleRegion(image: IntSize, container: Size, scale: Float, offset: Offset): Rect? {
    val f = fit(image, container)
    // Screen p relative to the center is q * scale + offset; q / f is the photo pixel from its center.
    fun x(screen: Float) = ((screen - container.width / 2 - offset.x) / scale / f + image.width / 2f).coerceIn(0f, image.width.toFloat())
    fun y(screen: Float) = ((screen - container.height / 2 - offset.y) / scale / f + image.height / 2f).coerceIn(0f, image.height.toFloat())
    val region = Rect(floor(x(0f)), floor(y(0f)), ceil(x(container.width)), kotlin.math.ceil(y(container.height)))
    return region.takeUnless { it.isEmpty }
}

/** Where a [region] of the photo in its pixels lands on screen; the inverse of [visibleRegion]. */
internal fun screenRect(region: Rect, image: IntSize, container: Size, scale: Float, offset: Offset): Rect {
    val f = fit(image, container) * scale
    fun x(px: Float) = (px - image.width / 2f) * f + offset.x + container.width / 2
    fun y(px: Float) = (px - image.height / 2f) * f + offset.y + container.height / 2
    return Rect(x(region.left), y(region.top), x(region.right), y(region.bottom))
}

/** The largest power of two that still gives at least one photo pixel per screen pixel. */
internal fun sampleSize(image: IntSize, container: Size, scale: Float): Int {
    val perScreenPixel = 1f / (fit(image, container) * scale)
    return if (perScreenPixel < 2f) 1 else 2f.pow(floor(log2(perScreenPixel))).toInt()
}

/** [region] of the photo as shown, in the pixels of the file before its EXIF turn of [degrees] clockwise. */
internal fun rawRect(region: Rect, raw: IntSize, degrees: Int): Rect = with(region) {
    when (degrees) {
        90 -> Rect(top, raw.height - right, bottom, raw.height - left)
        180 -> Rect(raw.width - right, raw.height - bottom, raw.width - left, raw.height - top)
        270 -> Rect(raw.width - bottom, left, raw.width - top, right)
        else -> region
    }
}

private const val SETTLE_MS = 150L
