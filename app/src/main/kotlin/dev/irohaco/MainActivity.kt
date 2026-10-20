package dev.irohaco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.irohaco.core.designsystem.motion.LocalSharedMediaScope
import dev.irohaco.core.designsystem.motion.SharedMediaScope
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.theme.SchemeContrast
import dev.irohaco.core.domain.ThemeColor
import dev.irohaco.core.domain.ThemeContrast
import dev.irohaco.feature.settings.SettingsScreen
import dev.irohaco.feature.settings.SettingsViewModel
import dev.irohaco.feature.timeline.TimelineScreen
import dev.irohaco.feature.timeline.TimelineViewModel
import dev.irohaco.feature.timeline.library.FolderScreen
import dev.irohaco.feature.timeline.library.FolderViewModel
import dev.irohaco.feature.timeline.library.LibraryScreen
import dev.irohaco.feature.timeline.library.LibraryViewModel
import dev.irohaco.core.contracts.FolderRef
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import dev.irohaco.feature.timeline.viewer.MediaViewerScreen
import dev.irohaco.feature.timeline.viewer.ViewerViewModel
import dev.irohaco.ui.DestinationPlaceholder
import dev.irohaco.ui.IrohacoApp
import dev.irohaco.ui.ScreenViewModelStores
import dev.irohaco.ui.TopLevelDestination
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as IrohacoApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Resume covers launch, returning from the permission dialog and returning from system settings.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { container.syncCatalog() }
        }
        setContent {
            val themeColor by container.themeColorSettings.observe().collectAsStateWithLifecycle(ThemeColor.Device)
            val contrast by container.themeColorSettings.observeContrast().collectAsStateWithLifecycle(ThemeContrast.DEVICE)
            IrohacoTheme(
                seedColor = (themeColor as? ThemeColor.Seed)?.let { Color(0xFF000000 or it.rgb.toLong()) },
                contrast = when (contrast) {
                    ThemeContrast.DEVICE -> null
                    ThemeContrast.STANDARD -> SchemeContrast.STANDARD
                    ThemeContrast.MEDIUM -> SchemeContrast.MEDIUM
                    ThemeContrast.HIGH -> SchemeContrast.HIGH
                },
            ) {
                // Full-screen pages stack over the shell, which keeps its saved state (tab, scroll position) meanwhile.
                var stack by rememberSaveable(saver = listSaver(save = { it.value }, restore = { mutableStateOf(it) })) {
                    mutableStateOf(listOf(Route.SHELL))
                }
                val open = { route: String -> stack = stack + route }
                val back = { stack = stack.dropLast(1).ifEmpty { listOf(Route.SHELL) } }
                val shellState = rememberSaveableStateHolder()
                val stores = viewModel { ScreenViewModelStores() }
                SharedTransitionLayout {
                    AnimatedContent(targetState = stack.last(), label = "route") { target ->
                        CompositionLocalProvider(LocalSharedMediaScope provides SharedMediaScope(this@SharedTransitionLayout, this)) {
                            if (target == Route.SHELL) {
                                shellState.SaveableStateProvider(Route.SHELL) {
                                    Shell(
                                        onOpenMedia = { open(Route.viewer(it)) },
                                        onOpenFolder = { open(Route.folder(it)) },
                                        onOpenSettings = { open(Route.SETTINGS) },
                                    )
                                }
                            } else {
                                RouteScope(target, stores, isOpen = { it in stack }) {
                                    FullScreen(target, open, back)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Shell(onOpenMedia: (String) -> Unit, onOpenFolder: (FolderRef) -> Unit, onOpenSettings: () -> Unit) {
        IrohacoApp { destination ->
            when (destination) {
                TopLevelDestination.PHOTOS -> TimelineScreen(
                    viewModel {
                        TimelineViewModel(
                            container.getTimelinePage,
                            container.getLibraryAccess,
                            container.organizeMedia,
                            container::syncCatalog,
                            container.browseLibrary.excludedFolders(),
                        )
                    },
                    onOpenMedia = onOpenMedia,
                    onOpenSettings = onOpenSettings,
                )
                TopLevelDestination.LIBRARY -> LibraryScreen(
                    viewModel { LibraryViewModel(container.browseLibrary) },
                    onOpenFolder = onOpenFolder,
                    onOpenSettings = onOpenSettings,
                )
                else -> DestinationPlaceholder(destination)
            }
        }
    }

    @Composable
    private fun FullScreen(route: String, open: (String) -> Unit, onBack: () -> Unit) {
        when {
            route == Route.SETTINGS -> SettingsScreen(viewModel { SettingsViewModel(container.themeColorSettings) }, onBack)
            Route.isFolder(route) -> {
                val folder = Route.parseFolder(route)
                FolderScreen(
                    viewModel { FolderViewModel(container.getTimelinePage, container.browseLibrary, folder) },
                    onOpenMedia = { open(Route.viewer(it, folder)) },
                    onBack = onBack,
                )
            }
            else -> {
                val viewer = Route.parseViewer(route)
                MediaViewerScreen(
                    viewModel {
                        val page = container.getTimelinePage
                        // From a folder, swiping stays in that folder; from the timeline, it follows the timeline.
                        val query = viewer.folder?.let { page.query.value.copy(folder = it, excludedFolders = emptySet()) } ?: page.query.value
                        ViewerViewModel(page, viewer.assetId, viewer.startMs, container::newVideoPlayer, container.getMediaDetails, query)
                    },
                    onBack,
                )
            }
        }
    }

    /** Gives the route its own ViewModelStore and clears it when the route closes for good (not on rotation). */
    @Composable
    private fun RouteScope(route: String, stores: ScreenViewModelStores, isOpen: (String) -> Boolean, content: @Composable () -> Unit) {
        val owner = remember(route) { stores.owner(route) }
        val stillOpen by rememberUpdatedState(isOpen)
        // A page covered by another one (a folder under its viewer) keeps its ViewModels for the way back.
        DisposableEffect(route) {
            onDispose { if (!isChangingConfigurations && !stillOpen(route)) stores.clear(route) }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
    }
}

/**
 * Routes over the shell, kept as strings so they survive in saved state. A NUL separates the folder
 * parts, because no volume id or path can contain one.
 */
object Route {
    const val SHELL = "shell"
    const val SETTINGS = "settings"
    private const val VIEWER = "viewer:"
    private const val FOLDER = "folder:"
    private const val SEP = '\u0000'

    data class Viewer(val assetId: String, val startMs: Long?, val folder: FolderRef?)

    /** The viewer over the timeline, or over one folder when [folder] is given. */
    fun viewer(assetId: String, folder: FolderRef? = null) = VIEWER + assetId + folder?.let { "$SEP${encode(it)}" }.orEmpty()

    /** PlayVideoAt (ARCHITECTURE §1.2): opens the viewer on a video and plays it from [startMs]. */
    fun playVideoAt(assetId: String, startMs: Long) = "$VIEWER$assetId@$startMs"

    fun parseViewer(route: String): Viewer {
        val parts = route.removePrefix(VIEWER).split(SEP, limit = 2)
        val head = parts[0]
        val at = head.lastIndexOf('@')
        val folder = parts.getOrNull(1)?.let(::decode)
        return if (at < 0) Viewer(head, null, folder) else Viewer(head.substring(0, at), head.substring(at + 1).toLongOrNull(), folder)
    }

    fun folder(folder: FolderRef) = FOLDER + encode(folder)

    fun isFolder(route: String) = route.startsWith(FOLDER)

    fun parseFolder(route: String): FolderRef = decode(route.removePrefix(FOLDER))

    private fun encode(folder: FolderRef) = "${folder.volumeId}$SEP${folder.relativePath}"

    private fun decode(value: String): FolderRef = value.split(SEP, limit = 2).let { FolderRef(it[0], it.getOrElse(1) { "" }) }
}
