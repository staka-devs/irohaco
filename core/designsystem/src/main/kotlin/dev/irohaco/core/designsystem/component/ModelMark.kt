package dev.irohaco.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/** A round mark with the model's first letter, so models tell apart at a glance. */
@Composable
fun ModelMark(name: String) {
    val colors = MaterialTheme.colorScheme
    val (fill, ink) = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
    )[Math.floorMod(name.substringBefore(' ').lowercase().hashCode(), 3)]
    Box(Modifier.size(24.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(), style = MaterialTheme.typography.labelMedium, color = ink)
    }
}

/** The model's name after its [ModelMark]. */
@Composable
fun MarkedModelName(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
        ModelMark(name)
        Text(name)
    }
}
