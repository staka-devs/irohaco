package dev.irohaco.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSeedColors
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.designsystem.tokens.legibleContentOn
import dev.irohaco.core.domain.ThemeColor

/** Settings (DESIGN §3, §4.5). Opened from the Photos tab's top app bar. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val themeColor by viewModel.themeColor.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    SettingsContent(themeColor, viewModel::select, onBack)
}

@Composable
internal fun SettingsContent(themeColor: ThemeColor, onSelect: (ThemeColor) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).selectableGroup()) {
            Text(
                stringResource(R.string.settings_theme_color),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme_color_device)) },
                supportingContent = { Text(stringResource(R.string.settings_theme_color_device_summary)) },
                leadingContent = { RadioButton(selected = themeColor == ThemeColor.Device, onClick = null) },
                modifier = Modifier.selectable(
                    selected = themeColor == ThemeColor.Device,
                    role = Role.RadioButton,
                    onClick = { onSelect(ThemeColor.Device) },
                ),
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
                verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
            ) {
                IrohacoSeedColors.forEach { option ->
                    val seed = ThemeColor.Seed(option.color.toArgb() and 0xFFFFFF)
                    Swatch(
                        color = option.color,
                        name = stringResource(option.name),
                        selected = themeColor == seed,
                        onClick = { onSelect(seed) },
                    )
                }
            }
        }
    }
}

/** A 48dp colour circle (DESIGN §7 touch target); the selected one carries a check in a legible colour. */
@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painterResource(IrohacoIcons.Check),
                contentDescription = null,
                tint = legibleContentOn(color),
            )
        }
    }
}

@IrohacoPreviews
@Composable
private fun SettingsPreview() {
    IrohacoTheme(dynamicColor = false) {
        SettingsContent(ThemeColor.Seed(0x2196F3), {}, {})
    }
}
