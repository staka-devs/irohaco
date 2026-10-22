package dev.irohaco.feature.settings

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.MapProvider
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ShareLocationPolicy
import dev.irohaco.core.designsystem.component.ThemeSwatch
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.designsystem.tokens.MaterialPalette
import dev.irohaco.core.designsystem.tokens.PaletteHue
import dev.irohaco.core.domain.ThemeColor
import dev.irohaco.core.domain.ThemeContrast
import kotlinx.coroutines.launch

/** Settings, in categories (DESIGN §2.1, §3, APP-001). Opened from the top app bars. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, googleMapsAvailable: Boolean = false) {
    val themeColor by viewModel.themeColor.collectAsStateWithLifecycle()
    val contrast by viewModel.contrast.collectAsStateWithLifecycle()
    val shareLocationPolicy by viewModel.shareLocationPolicy.collectAsStateWithLifecycle()
    val mapProvider by viewModel.mapProvider.collectAsStateWithLifecycle()
    val mapConsented by viewModel.mapConsented.collectAsStateWithLifecycle()
    val excludedFolders by viewModel.excludedFolders.collectAsStateWithLifecycle()
    val modelStates by viewModel.modelStates.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()
    val indexFolders by viewModel.indexFolders.collectAsStateWithLifecycle()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importModel(it.toString()) } }
    SettingsContent(
        onBack = onBack,
        themeColor = themeColor,
        contrast = contrast,
        excludedFolders = excludedFolders,
        shareLocationPolicy = shareLocationPolicy,
        modelSummary = viewModel.modelCatalog.firstOrNull()?.let { modelStates[it.id] } ?: ModelInstallState.NotInstalled,
        ai = {
            AiSettings(
                models = viewModel.modelCatalog,
                states = modelStates,
                importResult = importResult,
                onDownload = viewModel::downloadModel,
                onImport = { pickModel.launch(arrayOf("application/octet-stream", "*/*")) },
                onDelete = viewModel::deleteModel,
            )
            IndexingSettings(
                indexing = indexing,
                modelReady = viewModel.modelCatalog.any { modelStates[it.id] is ModelInstallState.Installed },
                folders = indexFolders,
                onStart = { folders ->
                    // The job runs either way; without the permission only its progress notification is hidden.
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    viewModel.startIndex(folders)
                },
                onPause = viewModel::pauseIndex,
                onResume = viewModel::resumeIndex,
                onCancel = viewModel::cancelIndex,
                onRetry = viewModel::retryIndex,
            )
        },
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
    AI(R.string.settings_ai, IrohacoIcons.SmartToy),
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
    modelSummary: ModelInstallState = ModelInstallState.NotInstalled,
    ai: @Composable () -> Unit = {},
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
                                SettingsCategory.DISPLAY -> themeColorSummary(themeColor) + " · " + stringResource(contrastLabel(contrast))
                                SettingsCategory.LIBRARY -> if (excludedFolders.isEmpty()) {
                                    stringResource(R.string.settings_library_none_hidden)
                                } else {
                                    pluralStringResource(R.plurals.settings_library_hidden, excludedFolders.size, excludedFolders.size)
                                }
                                SettingsCategory.PRIVACY -> stringResource(R.string.settings_share_location) + ": " + stringResource(sharePolicyLabel(shareLocationPolicy))
                                SettingsCategory.AI -> modelStateLabel(modelSummary)
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
                            SettingsCategory.AI -> ai()
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

/** The colour in use, by name when it is one of the hand-picked ones. */
@Composable
private fun themeColorSummary(color: ThemeColor): String = when {
    color !is ThemeColor.Seed -> stringResource(R.string.settings_theme_color_device)
    else -> curatedHueOf(color)?.let { stringResource(it.name) } ?: color.toHex()
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

private fun ThemeColor.Seed.toColor() = Color(0xFF000000 or rgb.toLong())

private fun Color.toSeed() = ThemeColor.Seed(toArgb() and 0xFFFFFF)

/** Ten hand-picked hues' 500 shades, apart enough to tell apart (APP-002). */
private val CURATED: List<PaletteHue>
    get() = listOf(0, 1, 2, 4, 5, 8, 9, 13, 14, 16).map { MaterialPalette[it] }

private fun curatedHueOf(seed: ThemeColor.Seed): PaletteHue? = CURATED.firstOrNull { it.main.toSeed() == seed }

/**
 * Theme colour and contrast (DESIGN §2.1, APP-002). The device's colour stays the default; the
 * hand-picked colours show the app in light and dark at a glance, and "Other colours" opens a picker.
 * Every choice applies at once.
 */
@Composable
internal fun DisplaySettings(
    themeColor: ThemeColor,
    contrast: ThemeContrast,
    onSelect: (ThemeColor) -> Unit,
    onSelectContrast: (ThemeContrast) -> Unit,
) {
    val seed = themeColor as? ThemeColor.Seed
    var choosingOther by rememberSaveable { mutableStateOf(false) }
    if (choosingOther) OtherColorDialog(current = seed, onSelect = onSelect, onDismiss = { choosingOther = false })
    SectionTitle(R.string.settings_theme_color)
    Column(Modifier.selectableGroup()) {
        RadioRow(R.string.settings_theme_color_device, seed == null, R.string.settings_theme_color_device_summary) { onSelect(ThemeColor.Device) }
    }
    FlowRow(
        modifier = Modifier.padding(horizontal = IrohacoSpacing.l).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
    ) {
        CURATED.forEach { hue ->
            val value = hue.main.toSeed()
            ThemeSwatch(seed = hue.main, name = stringResource(hue.name), selected = seed == value, onClick = { onSelect(value) })
        }
    }
    val other = seed?.takeIf { curatedHueOf(it) == null }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_theme_color_other)) },
        supportingContent = { Text(other?.toHex() ?: stringResource(R.string.settings_theme_color_other_summary)) },
        leadingContent = {
            Box(Modifier.size(24.dp).clip(CircleShape).background(other?.toColor() ?: MaterialTheme.colorScheme.surfaceVariant))
        },
        modifier = Modifier.clickable { choosingOther = true },
    )

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

/** Hue across and lightness down on one surface, and the hex code; each change applies at once. */
@Composable
private fun OtherColorDialog(current: ThemeColor.Seed?, onSelect: (ThemeColor) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme_color_other)) },
        text = { OtherColorBody(current, onSelect) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}

@Composable
private fun OtherColorBody(current: ThemeColor.Seed?, onSelect: (ThemeColor) -> Unit) {
    var picked by remember { mutableStateOf(current?.toColor()) }
    var hexKey by remember { mutableIntStateOf(0) } // a pick from the surface rewrites the hex field
    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m)) {
        HueLightnessPicker(picked) { color ->
            picked = color
            hexKey++
            onSelect(color.toSeed())
        }
        key(hexKey) {
            CustomColor(current = picked?.toSeed()) { chosen ->
                picked = (chosen as? ThemeColor.Seed)?.toColor()
                onSelect(chosen)
            }
        }
    }
}

private val PICKER_HUES = (0..6).map { Color.hsl(it * 60f % 360f, 0.8f, 0.5f) }

@Composable
private fun HueLightnessPicker(color: Color?, onPick: (Color) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val label = stringResource(R.string.settings_theme_color_picker)
    fun pick(at: Offset) {
        if (size.width == 0 || size.height == 0) return
        val hue = (at.x / size.width).coerceIn(0f, 1f) * 359f
        // The ends are cut off, as near-white and near-black seeds make the same grey theme.
        val lightness = (1f - (at.y / size.height)).coerceIn(0.15f, 0.85f)
        onPick(Color.hsl(hue, 0.8f, lightness))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(MaterialTheme.shapes.medium)
            .onSizeChanged { size = it }
            .drawBehind {
                drawRect(Brush.horizontalGradient(PICKER_HUES))
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.8f), Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                color?.let { drawCircle(Color.White, radius = 10.dp.toPx(), center = it.pickerOffset(this.size), style = Stroke(3.dp.toPx())) }
            }
            .pointerInput(Unit) { detectTapGestures { pick(it) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> pick(change.position) } }
            .semantics { contentDescription = label },
    )
}

/** Where a colour sits on the picker: its hue across, its lightness down. */
private fun Color.pickerOffset(size: Size): Offset {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }
    return Offset(hsl[0] / 359f * size.width, (1f - hsl[2]) * size.height)
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

@IrohacoPreviews
@Composable
private fun SettingsPalettePreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { Column(Modifier.verticalScroll(rememberScrollState())) { DisplaySettings(ThemeColor.Seed(0x2196F3), ThemeContrast.DEVICE, {}, {}) } }
    }
}

@IrohacoPreviews
@Composable
private fun SettingsCustomPreview() {
    IrohacoTheme(seedColor = Color(0xFF6A1B9A), contrast = 1.0) {
        Surface { Column(Modifier.verticalScroll(rememberScrollState())) { DisplaySettings(ThemeColor.Seed(0x6A1B9B), ThemeContrast.HIGH, {}, {}) } }
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

@IrohacoPreviews
@Composable
private fun OtherColorPreview() {
    // The dialog's body: in a dialog window the hex field takes focus, and its blinking cursor never lets the test settle.
    IrohacoTheme(dynamicColor = false) { Surface { Box(Modifier.padding(IrohacoSpacing.l)) { OtherColorBody(current = ThemeColor.Seed(0x6A1B9B), onSelect = {}) } } }
}
