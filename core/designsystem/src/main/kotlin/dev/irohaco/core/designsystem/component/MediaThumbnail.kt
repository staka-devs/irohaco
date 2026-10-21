package dev.irohaco.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

private const val SelectedScale = 0.9f

/**
 * A grid cell's image (GAL-001, M1-25): the app loads the small copy MediaStore keeps for the file
 * instead of decoding the original, and falls back to the original only when there is none.
 */
data class GridThumbnail(val contentUri: String) {
    /** The image loader's memory-cache key: one entry per file, whatever the cell size. */
    val memoryKey: String get() = "grid:$contentUri"
}

/**
 * A square grid cell for a photo or a video (DESIGN.md §2.3). Selecting it morphs the corners from
 * Extra small to Medium, shrinks it a little and shows a filled check, so selection is not shown by
 * shape alone. Clicks and long presses belong to the caller's [modifier].
 *
 * @param model anything Coil can load (a content Uri, a file); null shows only the placeholder.
 * @param contentDescription what TalkBack reads: kind, capture date and, for a video, its length.
 * @param label a short mark in the top end corner, such as "GIF" or a video's length; [contentDescription] already says it.
 * @param labelIcon an icon before [label], such as the play mark of a video; shown alone when [label] is null.
 */
@Composable
fun MediaThumbnail(
    model: Any?,
    contentDescription: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
    @DrawableRes labelIcon: Int? = null,
) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "thumbnailSelection",
    )
    val shape = LerpCornerShape(MaterialTheme.shapes.extraSmall, MaterialTheme.shapes.medium, progress)
    Box(
        modifier
            .aspectRatio(1f)
            // One node per cell: the badge and the check are already in the description, and with
            // accessibility services on, every node costs time on each scrolled frame (M1-34).
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val scale = lerp(1f, SelectedScale, progress)
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        if (label != null || labelIcon != null) {
            // White on a translucent black pill stays readable over both dark and bright photos.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(IrohacoSpacing.xs)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = IrohacoSpacing.xs),
            ) {
                if (labelIcon != null) Icon(painterResource(labelIcon), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        if (selected) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(IrohacoSpacing.xs),
            ) {
                Icon(painterResource(IrohacoIcons.CheckCircleFilled), contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Interpolates two uniform-corner theme shapes so the morph keeps using the theme's tokens. */
private class LerpCornerShape(
    private val from: CornerBasedShape,
    private val to: CornerBasedShape,
    private val fraction: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = lerp(from.topStart.toPx(size, density), to.topStart.toPx(size, density), fraction)
        return RoundedCornerShape(radius).createOutline(size, layoutDirection, density)
    }
}

@IrohacoPreviews
@Composable
private fun MediaThumbnailPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
                    MediaThumbnail(model = null, contentDescription = "Photo", selected = false, modifier = Modifier.size(120.dp))
                    MediaThumbnail(model = null, contentDescription = "Photo", selected = true, modifier = Modifier.size(120.dp))
                    // A video whose length MediaStore does not know.
                    MediaThumbnail(model = null, contentDescription = "Video", selected = false, modifier = Modifier.size(120.dp), labelIcon = IrohacoIcons.PlayArrowFilled)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
                    MediaThumbnail(model = null, contentDescription = "Animated image", selected = false, modifier = Modifier.size(120.dp), label = "GIF")
                    MediaThumbnail(
                        model = null,
                        contentDescription = "Video",
                        selected = false,
                        modifier = Modifier.size(120.dp),
                        label = "1:01:19",
                        labelIcon = IrohacoIcons.PlayArrowFilled,
                    )
                }
            }
        }
    }
}
