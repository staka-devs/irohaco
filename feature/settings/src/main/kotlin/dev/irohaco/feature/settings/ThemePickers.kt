package dev.irohaco.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import android.graphics.Color as AndroidColor

/**
 * The app as the colour [seed] makes it, in the light or dark the screen is in now: the bar, a card, a button, a chip
 * and the floating button, so the colour is judged by what it does rather than by the swatch alone.
 */
@Composable
internal fun ThemePreview(seed: Color, modifier: Modifier = Modifier) {
    IrohacoTheme(seedColor = seed, dynamicColor = false) {
        val scheme = MaterialTheme.colorScheme
        Surface(
            color = scheme.surface,
            shape = MaterialTheme.shapes.large,
            modifier = modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.large),
        ) {
            PreviewBody(scheme)
        }
    }
}

@Composable
private fun PreviewBody(scheme: ColorScheme) {
    Column {
        Surface(color = scheme.surfaceContainer) {
            Text(
                stringResource(R.string.settings_theme_preview),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(IrohacoSpacing.l),
            )
        }
        Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m)) {
            Card {
                Text(stringResource(R.string.settings_theme_preview_card), modifier = Modifier.padding(IrohacoSpacing.l))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Button(onClick = {}) { Text(stringResource(R.string.settings_theme_preview_button)) }
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.settings_theme_preview_chip)) })
            }
            Box(Modifier.fillMaxWidth()) {
                FloatingActionButton(onClick = {}, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd)) {
                    Icon(painterResource(IrohacoIcons.Add), contentDescription = null)
                }
            }
        }
    }
}

/**
 * Any colour, picked as most apps let one pick it: saturation and brightness across and down a square, the hue along a
 * bar under it. [onPick] gets the colour when a touch ends, so the theme is not made again on every move.
 */
@Composable
internal fun ColorPicker(color: Color, onPick: (Color) -> Unit, modifier: Modifier = Modifier) {
    val start = remember(color) { FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) } }
    var hue by remember(color) { mutableStateOf(start[0]) }
    var saturation by remember(color) { mutableStateOf(start[1]) }
    var value by remember(color) { mutableStateOf(start[2]) }
    fun current() = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
    val pure = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val described = stringResource(R.string.settings_color_picker)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m)) {
        Canvas(
            Modifier.fillMaxWidth().height(180.dp)
                .semantics { contentDescription = described }
                .pointerInput(Unit) {
                    fun at(offset: Offset) {
                        saturation = (offset.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                    }
                    detectTapGestures { at(it); onPick(current()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onPick(current()) }) { change, _ ->
                        saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val at = Offset(saturation * size.width, (1f - value) * size.height)
            drawCircle(Color.White, radius = 12.dp.toPx(), center = at, style = Stroke(3.dp.toPx()))
        }
        Canvas(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(Unit) { detectTapGestures { hue = (it.x / size.width).coerceIn(0f, 1f) * 360f; onPick(current()) } }
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onPick(current()) }) { change, _ -> hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f }
                },
        ) {
            drawRect(Brush.horizontalGradient((0..6).map { Color(AndroidColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
            drawCircle(Color.White, radius = 12.dp.toPx(), center = Offset(hue / 360f * size.width, size.height / 2), style = Stroke(3.dp.toPx()))
        }
        Box(Modifier.size(40.dp).background(current(), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
    }
}

@IrohacoPreviews
@Composable
private fun ThemePreviewPreview() {
    IrohacoTheme(dynamicColor = false) {
        Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.l)) {
            ThemePreview(Color(0xFFE91E63))
            ColorPicker(Color(0xFF009688), onPick = {})
        }
    }
}
