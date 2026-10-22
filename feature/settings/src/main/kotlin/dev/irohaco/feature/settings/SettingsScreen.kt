package dev.irohaco.feature.settings

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irohaco.core.contracts.FolderRef
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
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val themeColor by viewModel.themeColor.collectAsStateWithLifecycle()
    val contrast by viewModel.contrast.collectAsStateWithLifecycle()
    val shareLocationPolicy by viewModel.shareLocationPolicy.collectAsStateWithLifecycle()
    val mapAllowed by viewModel.mapAllowed.collectAsStateWithLifecycle()
    val excludedFolders by viewModel.excludedFolders.collectAsStateWithLifecycle()
    val modelStates by viewModel.modelStates.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()
    val indexFolders by viewModel.indexFolders.collectAsStateWithLifecycle()
    val lowCoverageVideos by viewModel.lowCoverageVideos.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val autoIndex by viewModel.autoIndex.collectAsStateWithLifecycle()
    val needsRebuild by viewModel.needsRebuild.collectAsStateWithLifecycle()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importModel(it.toString()) } }
    SettingsContent(
        onBack = onBack,
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
                lowCoverageVideos = lowCoverageVideos,
                pendingCount = pendingCount,
                autoIndex = autoIndex,
                onAutoIndex = viewModel::setAutoIndex,
                needsRebuild = needsRebuild,
                onStart = { folders, quality ->
                    // The job runs either way; without the permission only its progress notification is hidden.
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    viewModel.startIndex(folders, quality)
                },
                onPause = viewModel::pauseIndex,
                onResume = viewModel::resumeIndex,
                onCancel = viewModel::cancelIndex,
                onRetry = viewModel::retryIndex,
            )
        },
        display = { DisplaySettings(themeColor, contrast, viewModel::select, viewModel::selectContrast) },
        library = { LibrarySettings(excludedFolders, viewModel::showFolder) },
        privacy = { PrivacySettings(shareLocationPolicy, viewModel::selectShareLocationPolicy, mapAllowed, viewModel::setMapAllowed) },
    )
}

/** The categories; each opens its own page, with the same name as its title, and says what can be set there. */
enum class SettingsCategory(@StringRes val title: Int, @StringRes val summary: Int, @DrawableRes val icon: Int) {
    DISPLAY(R.string.settings_display, R.string.settings_display_summary, IrohacoIcons.Visibility),
    LIBRARY(R.string.settings_library, R.string.settings_library_summary, IrohacoIcons.Folder),
    PRIVACY(R.string.settings_privacy, R.string.settings_privacy_summary, IrohacoIcons.Lock),
    AI(R.string.settings_ai, R.string.settings_ai_summary, IrohacoIcons.SmartToy),
}

/**
 * The category list, and the chosen category beside it on Expanded widths or over it on narrower
 * ones (ListDetailPaneScaffold). Each category says in one line what it holds, not its values.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun SettingsContent(
    onBack: () -> Unit,
    display: @Composable () -> Unit,
    library: @Composable () -> Unit,
    privacy: @Composable () -> Unit,
    initial: SettingsCategory? = null,
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
                            ListItem(
                                headlineContent = { Text(stringResource(category.title)) },
                                supportingContent = { Text(stringResource(category.summary), maxLines = 1) },
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

/**
 * Folders hidden from the Photos tab (GAL-002), which the folder's own page in the Library hides;
 * each comes back here with one tap.
 */
@Composable
internal fun LibrarySettings(excludedFolders: List<FolderRef>, onShow: (FolderRef) -> Unit) {
    SectionTitle(R.string.settings_library_hidden_title)
    Text(
        stringResource(if (excludedFolders.isEmpty()) R.string.settings_library_none_hidden else R.string.settings_library_hidden_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
    )
    excludedFolders.forEach { folder ->
        ListItem(
            headlineContent = { Text(folder.relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { folder.volumeId }) },
            supportingContent = { Text(folder.relativePath.ifEmpty { "/" }) },
            leadingContent = { Icon(painterResource(IrohacoIcons.VisibilityOff), contentDescription = null) },
            trailingContent = { TextButton(onClick = { onShow(folder) }) { Text(stringResource(R.string.settings_library_show)) } },
        )
    }
}

private fun Color.toSeed() = ThemeColor.Seed(toArgb() and 0xFFFFFF)

/** Basic colours: the 500 shades of ten hues, apart enough to tell apart (APP-002). Blue is the default seed. */
private val BASIC: List<PaletteHue>
    get() = listOf(0, 1, 2, 4, 5, 8, 9, 13, 14, 16).map { MaterialPalette[it] }

/**
 * Theme colour and contrast (DESIGN §2.1, APP-002), picked the way Android picks its own: the device's
 * colours, the default, then the basic colours, as round swatches. Every choice applies at once.
 */
@Composable
internal fun DisplaySettings(
    themeColor: ThemeColor,
    contrast: ThemeContrast,
    onSelect: (ThemeColor) -> Unit,
    onSelectContrast: (ThemeContrast) -> Unit,
) {
    val seed = themeColor as? ThemeColor.Seed
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val device = remember(dark) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
    val chosen = BASIC.firstOrNull { it.main.toSeed() == seed }
    SectionTitle(R.string.settings_theme_color)
    FlowRow(
        modifier = Modifier.padding(horizontal = IrohacoSpacing.l).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
    ) {
        ThemeSwatch(device, stringResource(R.string.settings_theme_color_device), selected = seed == null, onClick = { onSelect(ThemeColor.Device) })
        BASIC.forEach { hue ->
            val value = hue.main.toSeed()
            ThemeSwatch(seed = hue.main, name = stringResource(hue.name), selected = seed == value, onClick = { onSelect(value) })
        }
    }
    // The swatches carry no text; the one in use is named below them.
    Text(
        when {
            seed == null -> stringResource(R.string.settings_theme_color_device)
            chosen != null -> stringResource(chosen.name)
            else -> seed.toHex() // a colour picked in an earlier version, kept until another is chosen
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
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

/** What leaves the device: a shared photo's GPS (MET-003) and the online map (ADR-0011). */
@Composable
internal fun PrivacySettings(
    shareLocationPolicy: ShareLocationPolicy,
    onSelectShareLocationPolicy: (ShareLocationPolicy) -> Unit,
    mapAllowed: Boolean,
    onMapAllowed: (Boolean) -> Unit,
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
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_map_allow)) },
        supportingContent = { Text(stringResource(R.string.settings_map_allow_summary)) },
        trailingContent = { Switch(checked = mapAllowed, onCheckedChange = null) },
        modifier = Modifier.toggleable(mapAllowed, role = Role.Switch, onValueChange = onMapAllowed),
    )
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
private fun RadioRow(label: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
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
private fun SettingsCategoriesPreview() {
    IrohacoTheme(dynamicColor = false) {
        SettingsContent(onBack = {}, display = {}, library = {}, privacy = {})
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
        Surface { Column { PrivacySettings(ShareLocationPolicy.STRIP, {}, mapAllowed = true, {}) } }
    }
}

@Preview(name = "Expanded", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun SettingsExpandedPreview() {
    IrohacoTheme(dynamicColor = false) {
        SettingsContent(
            onBack = {},
            display = { DisplaySettings(ThemeColor.Device, ThemeContrast.STANDARD, {}, {}) },
            library = {},
            privacy = {},
            initial = SettingsCategory.DISPLAY,
        )
    }
}
