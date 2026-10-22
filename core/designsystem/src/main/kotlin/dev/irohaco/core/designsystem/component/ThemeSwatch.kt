package dev.irohaco.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamicColorScheme
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/** A theme colour made from [seed], as a round swatch (APP-002). */
@Composable
fun ThemeSwatch(seed: Color, name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = remember(seed) { dynamicColorScheme(seedColor = seed, isDark = false) }
    ThemeSwatch(scheme, name, selected, onClick, modifier)
}

/**
 * A theme as Android's own colour picker shows one (Material 3): a circle of its primary colour over
 * its secondary and tertiary ones, ringed and checked when in use. TalkBack reads the name and
 * whether it is the one in use.
 */
@Composable
fun ThemeSwatch(scheme: ColorScheme, name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(64.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = name
                this.selected = selected
                role = Role.RadioButton
            }
            .padding(if (selected) 6.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxSize().clip(CircleShape)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(scheme.primary))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.fillMaxSize().weight(1f).background(scheme.secondaryContainer))
                Box(Modifier.fillMaxSize().weight(1f).background(scheme.tertiaryContainer))
            }
        }
        if (selected) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                Icon(painterResource(IrohacoIcons.Check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(2.dp).size(18.dp))
            }
        }
    }
}

@IrohacoPreviews
@Composable
private fun ThemeSwatchPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Row(Modifier.padding(IrohacoSpacing.l)) {
                ThemeSwatch(Color(0xFF2196F3), "Blue", selected = true, onClick = {})
                ThemeSwatch(Color(0xFF4CAF50), "Green", selected = false, onClick = {}, modifier = Modifier.padding(start = IrohacoSpacing.s))
            }
        }
    }
}
