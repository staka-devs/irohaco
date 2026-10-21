package dev.irohaco.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamicColorScheme
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/**
 * A theme colour as the app would look with it (APP-002): the main colours of the light scheme on
 * the left and of the dark one on the right, with its name below. TalkBack reads the name, not the
 * colours, and whether it is the one in use.
 */
@Composable
fun ThemeSwatch(seed: Color, name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val light = remember(seed) { dynamicColorScheme(seedColor = seed, isDark = false) }
    val dark = remember(seed) { dynamicColorScheme(seedColor = seed, isDark = true) }
    Column(
        modifier
            .width(104.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = name
                this.selected = selected
                role = Role.RadioButton
            }
            .padding(IrohacoSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs),
    ) {
        Box {
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.medium) else Modifier),
            ) {
                SchemeHalf(light)
                SchemeHalf(dark)
            }
            if (selected) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.align(Alignment.TopEnd).padding(IrohacoSpacing.xs)) {
                    Icon(painterResource(IrohacoIcons.CheckCircleFilled), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The surface with primary, secondary and tertiary dots, as a half of the swatch. */
@Composable
private fun SchemeHalf(scheme: ColorScheme) {
    Column(
        Modifier.width(48.dp).background(scheme.surfaceContainer).padding(IrohacoSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs),
    ) {
        Box(Modifier.fillMaxWidth().size(12.dp).clip(CircleShape).background(scheme.primary))
        Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(scheme.secondary))
            Box(Modifier.size(12.dp).clip(CircleShape).background(scheme.tertiary))
        }
        Box(Modifier.fillMaxWidth().size(8.dp).clip(CircleShape).background(scheme.primaryContainer))
    }
}

@IrohacoPreviews
@Composable
private fun ThemeSwatchPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Row(Modifier.padding(IrohacoSpacing.l), horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                ThemeSwatch(Color(0xFF2196F3), "Blue", selected = true, onClick = {})
                ThemeSwatch(Color(0xFF4CAF50), "Green", selected = false, onClick = {})
            }
        }
    }
}
