package dev.irohaco.core.designsystem.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.irohaco.core.contracts.ModelMarkStyle
import dev.irohaco.core.designsystem.R
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/** The marks the user set, by model id; the app provides them at its root. */
val LocalModelMarks = staticCompositionLocalOf<Map<String, ModelMarkStyle>> { emptyMap() }

/**
 * A round mark with the model's first letter, so models tell apart at a glance. The user may give a
 * model [id] its own color or a picture, such as the service's logo.
 */
@Composable
fun ModelMark(name: String, id: String? = null, modifier: Modifier = Modifier) {
    val style = id?.let { LocalModelMarks.current[it] }
    val picture = remember(style?.image) { style?.image?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    val colors = MaterialTheme.colorScheme
    val (fill, ink) = style?.color?.let { Color(it) to (if (Color(it).luminance() > 0.5f) Color.Black else Color.White) } ?: listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
    )[Math.floorMod(name.substringBefore(' ').lowercase().hashCode(), 3)]
    Box(modifier.size(24.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        if (picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // A picture, not a line of text: the letter keeps to the circle at any font size.
            val size = with(LocalDensity.current) { 13.dp.toSp() }
            Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(), style = MaterialTheme.typography.labelMedium, fontSize = size, lineHeight = size, color = ink)
        }
    }
}

/** The model's name after its [ModelMark]; [onMark] makes the mark a button that changes it. */
@Composable
fun MarkedModelName(name: String, id: String? = null, onMark: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
        val label = stringResource(R.string.model_mark_change, name)
        ModelMark(name, id, if (onMark == null) Modifier else Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onMark))
        Text(name)
    }
}
