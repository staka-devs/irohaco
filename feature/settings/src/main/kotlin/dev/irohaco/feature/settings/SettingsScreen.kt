package dev.irohaco.feature.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.pluralStringResource
import dev.irohaco.core.contracts.FolderRef
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.designsystem.tokens.MaterialPalette
import dev.irohaco.core.designsystem.tokens.PaletteHue
import dev.irohaco.core.designsystem.tokens.legibleContentOn
import dev.irohaco.core.contracts.MapProvider
import dev.irohaco.core.contracts.ShareLocationPolicy
import androidx.compose.foundation.clickable
import dev.irohaco.core.domain.ThemeColor
import dev.irohaco.core.domain.ThemeContrast

/** Settings, in categories (DESIGN §2.1, §3, APP-001). Opened from the top app bars. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, googleMapsAvailable: Boolean = false) {
    val themeColor by viewModel.themeColor.collectAsStateWithLifecycle()
    val contrast by viewModel.contrast.collectAsStateWithLifecycle()
    val shareLocationPolicy by viewModel.shareLocationPolicy.collectAsStateWithLifecycle()
    val mapProvider by viewModel.mapProvider.collectAsStateWithLifecycle()
    val mapConsented by viewModel.mapConsented.collectAsStateWithLifecycle()
    val excludedFolders by viewModel.excludedFolders.collectAsStateWithLifecycle()
    SettingsContent(
        onBack = onBack,
        themeColor = themeColor,
        contrast = contrast,
        excludedFolders = excludedFolders,
        shareLocationPolicy = shareLocationPolicy,
        display = { DisplaySettings(themeColor, contrast, viewModel::select, viewModel::selectContrast) },
        library = { LibrarySettings(excludedFolders, viewModel::showFolder) },
        privacy = {
            PrivacySettings(
                shareLocationPolicy = shareLocationPolicy,
                onSelectShareLocationPolicy = viewModel::selectShareLocationPolicy,
                mapProvider = mapProvider,
                googleMapsAvailable = googleMapsAvailable,
                onSelectMapProvider = viewModel::selectMapProvider,
                mapConsented = mapConsented.isNotEmpty(),
                onRevokeMapConsent = viewModel::revokeMapConsent,
            )
        },
    )
}

/** The categories; each opens its own page, with the same name as its title. */
enum class SettingsCategory(@StringRes val title: Int, @DrawableRes val icon: Int) {
    DISPLAY(R.string.settings_display, IrohacoIcons.Visibility),
    LIBRARY(R.string.settings_library, IrohacoIcons.Folder),
    PRIVACY(R.string.settings_privacy, IrohacoIcons.Lock),
}

/**
 * The category list, and the chosen category beside it on Expanded widths or over it on narrower
 * ones (ListDetailPaneScaffold). Each category shows its main setting's value in one line.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun SettingsContent(
    onBack: () -> Unit,
    themeColor: ThemeColor,
    contrast: ThemeContrast,
    excludedFolders: List<FolderRef>,
    shareLocationPolicy: ShareLocationPolicy,
    display: @Composable () -> Unit,
    library: @Composable () -> Unit,
    privacy: @Composable () -> Unit,
    initial: SettingsCategory? = null,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator(initialDestinationHistory = initialHistory(initial))
    val scope = rememberCoroutineScope()
    val listHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
    BackHandler(enabled = !navigator.canNavigateBack(), onBack = onBack)
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.settings_title)) },
                            navigationIcon = {
                                IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.settings_back)) }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        SettingsCategory.entries.forEach { category ->
                            val summary = when (category) {
                                SettingsCategory.DISPLAY -> stringResource(themeColorLabel(themeColor)) + " · " + stringResource(contrastLabel(contrast))
                                SettingsCategory.LIBRARY -> if (excludedFolders.isEmpty()) {
                                    stringResource(R.string.settings_library_none_hidden)
                                } else {
                                    pluralStringResource(R.plurals.settings_library_hidden, excludedFolders.size, excludedFolders.size)
                                }
                                SettingsCategory.PRIVACY -> stringResource(R.string.settings_share_location) + ": " + stringResource(sharePolicyLabel(shareLocationPolicy))
                            }
                            ListItem(
                                headlineContent = { Text(stringResource(category.title)) },
                                supportingContent = { Text(summary, maxLines = 1) },
                                leadingContent = { Icon(painterResource(category.icon), contentDescription = null) },
                                modifier = Modifier.clickable { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, category) } },
                            )
                        }
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                val category = navigator.currentDestination?.contentKey
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(category?.let { stringResource(it.title) }.orEmpty()) },
                            navigationIcon = {
                                if (listHidden) {
                                    IconButton(onClick = { scope.launch { navigator.navigateBack() } }) {
                                        Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.settings_back))
                                    }
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                        when (category) {
                            SettingsCategory.DISPLAY -> display()
                            SettingsCategory.LIBRARY -> library()
                            SettingsCategory.PRIVACY -> privacy()
                            null -> Text(
                                stringResource(R.string.settings_pick_category),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(IrohacoSpacing.xl),
                            )
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun initialHistory(initial: SettingsCategory?) =
    listOfNotNull(ThreePaneScaffoldDestinationItem<SettingsCategory>(ListDetailPaneScaffoldRole.List), initial?.let { ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail, it) })

private fun themeColorLabel(color: ThemeColor): Int = when {
    color !is ThemeColor.Seed -> R.string.settings_theme_color_device
    paletteHueOf(color) != null -> R.string.settings_theme_color_palette
    else -> R.string.settings_theme_color_custom
}

private fun contrastLabel(contrast: ThemeContrast): Int = when (contrast) {
    ThemeContrast.DEVICE -> R.string.settings_contrast_device
    ThemeContrast.STANDARD -> R.string.settings_contrast_standard
    ThemeContrast.MEDIUM -> R.string.settings_contrast_medium
    ThemeContrast.HIGH -> R.string.settings_contrast_high
}

private fun sharePolicyLabel(policy: ShareLocationPolicy): Int = when (policy) {
    ShareLocationPolicy.ASK -> R.string.settings_share_location_ask
    ShareLocationPolicy.STRIP -> R.string.settings_share_location_strip
    ShareLocationPolicy.KEEP -> R.string.settings_share_location_keep
}

/** Folders hidden from the Photos tab (GAL-002); each comes back with one tap. */
@Composable
internal fun LibrarySettings(excludedFolders: List<FolderRef>, onShow: (FolderRef) -> Unit) {
    SectionTitle(R.string.settings_library_hidden_title)
    if (excludedFolders.isEmpty()) {
        Text(
            stringResource(R.string.settings_library_none_hidden),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
        )
    }
    excludedFolders.forEach { folder ->
        ListItem(
            headlineContent = { Text(folder.relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { folder.volumeId }) },
            supportingContent = { Text(folder.relativePath.ifEmpty { "/" }) },
            leadingContent = { Icon(painterResource(IrohacoIcons.VisibilityOff), contentDescription = null) },
            trailingContent = { TextButton(onClick = { onShow(folder) }) { Text(stringResource(R.string.settings_library_show)) } },
        )
    }
}

private enum class ColorMode { DEVICE, PALETTE, CUSTOM }

private fun ThemeColor.Seed.toColor() = Color(0xFF000000 or rgb.toLong())

private fun Color.toSeed() = ThemeColor.Seed(toArgb() and 0xFFFFFF)

/** The hue holding [seed], or null when it is not a palette colour. */
private fun paletteHueOf(seed: ThemeColor.Seed): Int? =
    MaterialPalette.indexOfFirst { hue -> hue.shades.any { it.color.toSeed() == seed } }.takeIf { it >= 0 }

/** Theme colour and contrast (DESIGN §2.1). */
@Composable
internal fun DisplaySettings(
    themeColor: ThemeColor,
    contrast: ThemeContrast,
    onSelect: (ThemeColor) -> Unit,
    onSelectContrast: (ThemeContrast) -> Unit,
) {
    val seed = themeColor as? ThemeColor.Seed
    var mode by rememberSaveable {
        mutableStateOf(
            when {
                seed == null -> ColorMode.DEVICE
                paletteHueOf(seed) != null -> ColorMode.PALETTE
                else -> ColorMode.CUSTOM
            },
        )
    }
    var hue by rememberSaveable { mutableIntStateOf(seed?.let(::paletteHueOf) ?: BLUE) }
    SectionTitle(R.string.settings_theme_color)
    Column(Modifier.selectableGroup()) {
        RadioRow(R.string.settings_theme_color_device, mode == ColorMode.DEVICE, R.string.settings_theme_color_device_summary) {
            mode = ColorMode.DEVICE
            onSelect(ThemeColor.Device)
        }
        RadioRow(R.string.settings_theme_color_palette, mode == ColorMode.PALETTE) {
            mode = ColorMode.PALETTE
            onSelect(MaterialPalette[hue].main.toSeed())
        }
        RadioRow(R.string.settings_theme_color_custom, mode == ColorMode.CUSTOM) { mode = ColorMode.CUSTOM }
    }
    when (mode) {
        ColorMode.PALETTE -> PalettePicker(selectedHue = hue, selected = seed, onHue = { hue = it }, onSelect = onSelect)
        ColorMode.CUSTOM -> CustomColor(current = seed, onSelect = onSelect)
        ColorMode.DEVICE -> Unit
    }

    SectionTitle(R.string.settings_contrast)
    Column(Modifier.selectableGroup()) {
        listOf(
            ThemeContrast.DEVICE to R.string.settings_contrast_device,
            ThemeContrast.STANDARD to R.string.settings_contrast_standard,
            ThemeContrast.MEDIUM to R.string.settings_contrast_medium,
            ThemeContrast.HIGH to R.string.settings_contrast_high,
        ).forEach { (value, label) -> RadioRow(label, contrast == value) { onSelectContrast(value) } }
    }
}

/** What leaves the device: a shared photo's GPS (MET-003) and the online map (ADR-0011). */
@Composable
internal fun PrivacySettings(
    shareLocationPolicy: ShareLocationPolicy,
    onSelectShareLocationPolicy: (ShareLocationPolicy) -> Unit,
    mapProvider: MapProvider,
    googleMapsAvailable: Boolean,
    onSelectMapProvider: (MapProvider) -> Unit,
    mapConsented: Boolean,
    onRevokeMapConsent: () -> Unit,
) {
    SectionTitle(R.string.settings_share_location)
    Column(Modifier.selectableGroup()) {
        listOf(
            ShareLocationPolicy.ASK to R.string.settings_share_location_ask,
            ShareLocationPolicy.STRIP to R.string.settings_share_location_strip,
            ShareLocationPolicy.KEEP to R.string.settings_share_location_keep,
        ).forEach { (value, label) -> RadioRow(label, shareLocationPolicy == value) { onSelectShareLocationPolicy(value) } }
    }

    SectionTitle(R.string.settings_map)
    Column(Modifier.selectableGroup()) {
        RadioRow(R.string.settings_map_openfreemap, mapProvider == MapProvider.OPEN_FREE_MAP, R.string.settings_map_openfreemap_summary) {
            onSelectMapProvider(MapProvider.OPEN_FREE_MAP)
        }
        RadioRow(
            R.string.settings_map_google,
            mapProvider == MapProvider.GOOGLE,
            if (googleMapsAvailable) R.string.settings_map_google_summary else R.string.settings_map_google_unavailable,
            enabled = googleMapsAvailable,
        ) { onSelectMapProvider(MapProvider.GOOGLE) }
    }
    if (mapConsented) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_map_revoke)) },
            supportingContent = { Text(stringResource(R.string.settings_map_revoke_summary)) },
            modifier = Modifier.clickable(onClick = onRevokeMapConsent),
        )
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = IrohacoSpacing.l, end = IrohacoSpacing.l, top = IrohacoSpacing.l, bottom = IrohacoSpacing.s),
    )
}

@Composable
private fun RadioRow(label: Int, selected: Boolean, summary: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = summary?.let { { Text(stringResource(it)) } },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    )
}

/** The hues as a row of 500 shades, and the fourteen shades of the chosen hue below it. */
@Composable
private fun PalettePicker(selectedHue: Int, selected: ThemeColor.Seed?, onHue: (Int) -> Unit, onSelect: (ThemeColor) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = IrohacoSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
        modifier = Modifier.selectableGroup(),
    ) {
        itemsIndexed(MaterialPalette) { index, hue ->
            Swatch(hue.main, stringResource(hue.name), label = null, selected = index == selectedHue) { onHue(index) }
        }
    }
    val hue: PaletteHue = MaterialPalette[selectedHue]
    val hueName = stringResource(hue.name)
    FlowRow(
        modifier = Modifier.padding(IrohacoSpacing.l).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
    ) {
        hue.shades.forEach { shade ->
            val value = shade.color.toSeed()
            Swatch(
                color = shade.color,
                name = stringResource(R.string.settings_palette_shade, hueName, shade.label),
                label = shade.label,
                selected = value == selected,
            ) { onSelect(value) }
        }
    }
}

@Composable
private fun CustomColor(current: ThemeColor.Seed?, onSelect: (ThemeColor) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current?.toHex() ?: "#") }
    val parsed = ThemeColor.Seed.parseHex(text)
    Box(Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s)) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                ThemeColor.Seed.parseHex(input)?.let(onSelect) // only a valid colour is applied
            },
            label = { Text(stringResource(R.string.settings_custom_hex_label)) },
            isError = text.length > 1 && parsed == null,
            supportingText = if (text.length > 1 && parsed == null) {
                { Text(stringResource(R.string.settings_custom_hex_error)) }
            } else {
                null
            },
            leadingIcon = parsed?.let { { Box(Modifier.size(24.dp).clip(CircleShape).background(it.toColor())) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A 48dp colour circle (DESIGN §7 touch target) read out by name; the selected one is ringed and checked. */
@Composable
private fun Swatch(color: Color, name: String, label: String?, selected: Boolean, onClick: () -> Unit) {
    val content = legibleContentOn(color)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        when {
            selected -> Icon(painterResource(IrohacoIcons.Check), contentDescription = null, tint = content)
            label != null -> Text(label, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

private const val BLUE = 5

@IrohacoPreviews
@Composable
private fun SettingsPalettePreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { Column { DisplaySettings(ThemeColor.Seed(0x2196F3), ThemeContrast.DEVICE, {}, {}) } }
    }
}

@IrohacoPreviews
@Composable
private fun SettingsCustomPreview() {
    IrohacoTheme(seedColor = Color(0xFF6A1B9A), contrast = 1.0) {
        Surface { Column { DisplaySettings(ThemeColor.Seed(0x6A1B9B), ThemeContrast.HIGH, {}, {}) } }
    }
}

@IrohacoPreviews
@Composable
private fun SettingsCategoriesPreview() {
    IrohacoTheme(dynamicColor = false) {
        SettingsContent(
            onBack = {}, themeColor = ThemeColor.Device, contrast = ThemeContrast.DEVICE,
            excludedFolders = listOf(FolderRef("external_primary", "Pictures/Screenshots/")),
            shareLocationPolicy = ShareLocationPolicy.ASK, display = {}, library = {}, privacy = {},
        )
    }
}

@IrohacoPreviews
@Composable
private fun SettingsLibraryPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { Column { LibrarySettings(listOf(FolderRef("external_primary", "Pictures/Screenshots/"), FolderRef("external_primary", "Download/")), {}) } }
    }
}

@IrohacoPreviews
@Composable
private fun SettingsPrivacyPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Column {
                PrivacySettings(ShareLocationPolicy.STRIP, {}, MapProvider.OPEN_FREE_MAP, googleMapsAvailable = false, {}, mapConsented = true, {})
            }
        }
    }
}

@Preview(name = "Expanded", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun SettingsExpandedPreview() {
    IrohacoTheme(dynamicColor = false) {
        SettingsContent(
            onBack = {}, themeColor = ThemeColor.Device, contrast = ThemeContrast.STANDARD, excludedFolders = emptyList(),
            shareLocationPolicy = ShareLocationPolicy.ASK,
            display = { DisplaySettings(ThemeColor.Device, ThemeContrast.STANDARD, {}, {}) }, library = {}, privacy = {},
            initial = SettingsCategory.DISPLAY,
        )
    }
}
