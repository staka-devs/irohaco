package dev.irohaco.feature.timeline.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Scale and translation of a photo shown with ContentScale.Fit, drawn as `p = q * scale + offset`
 * around the center of a [Size] container. The offset is clamped so the scaled container never
 * leaves a gap at its edges, and scale 1 always means no offset.
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > 1f

    /** Applies a pinch/pan step; [centroid] is relative to the container center and stays under the fingers. */
    fun transform(zoomChange: Float, pan: Offset, centroid: Offset, size: Size) {
        val newScale = (scale * zoomChange).coerceIn(1f, MAX_SCALE)
        val anchored = centroid - (centroid - offset) * (newScale / scale)
        scale = newScale
        offset = clamp(anchored + pan, size)
    }

    /** Double tap: zoom in on [at] (relative to the center), or back to fit when already zoomed. */
    fun toggle(at: Offset, size: Size) {
        if (isZoomed) reset() else transform(DOUBLE_TAP_SCALE, Offset.Zero, at, size)
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private fun clamp(value: Offset, size: Size): Offset {
        val maxX = (scale - 1f) * size.width / 2f
        val maxY = (scale - 1f) * size.height / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}
