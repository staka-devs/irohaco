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
import dev.irohaco.feature.timeline.library.AlbumsScreen
import dev.irohaco.feature.timeline.library.AlbumsViewModel
import dev.irohaco.feature.timeline.library.CollectionScreen
import dev.irohaco.feature.timeline.library.CollectionViewModel
import dev.irohaco.feature.timeline.library.IdentityScreen
import dev.irohaco.feature.timeline.library.IdentityViewModel
import dev.irohaco.feature.timeline.library.MediaList
import dev.irohaco.feature.timeline.library.FolderViewModel
import dev.irohaco.feature.timeline.library.LibraryScreen
import dev.irohaco.feature.timeline.library.LibraryViewModel
import dev.irohaco.feature.timeline.library.MapScreen
import dev.irohaco.feature.timeline.library.MapViewModel
import dev.irohaco.feature.timeline.library.TrashScreen
import dev.irohaco.feature.timeline.library.TrashViewModel
import dev.irohaco.feature.timeline.search.SearchScreen
import dev.irohaco.feature.timeline.search.SearchViewModel
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.domain.GeoBounds
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import dev.irohaco.feature.timeline.viewer.MediaViewerScreen
import dev.irohaco.feature.timeline.viewer.ViewerViewModel
import dev.irohaco.ui.IrohacoApp
import dev.irohaco.ui.ScreenViewModelStores
import dev.irohaco.ui.TopLevelDestination
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as IrohacoApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container.resumeModelDownloads()
        // Resume covers launch, returning from the permission dialog and returning from system settings.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                container.syncCatalog()
                container.prepareShare.cleanUp() // stale share-cache copies past their retention window (MET-003)
                container.indexLocations() // GPS of new files, a few at a time; pausing the app pauses it (GAL-017)
            }
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
                                        onOpenMedia = { assetId, list -> open(Route.viewer(assetId, list)) },
                                        onOpenList = { open(Route.list(it)) },
                                        onOpenCandidates = { open(Route.IDENTITY) },
                                        onOpenSettings = { open(Route.SETTINGS) },
                                        onOpenTrash = { open(Route.TRASH) },
                                        onOpenMap = { open(Route.MAP) },
                                        onPlayAt = { assetId, ms -> open(Route.playVideoAt(assetId, ms)) },
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
    private fun Shell(
        onOpenMedia: (assetId: String, list: MediaList?) -> Unit,
        onOpenList: (MediaList) -> Unit,
        onOpenSettings: () -> Unit,
        onOpenCandidates: () -> Unit,
        onOpenTrash: () -> Unit,
        onOpenMap: () -> Unit,
        onPlayAt: (assetId: String, startMs: Long) -> Unit,
    ) {
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
                            container.markMedia,
                            container.manageCollections,
                            container.timelineSettings,
                            container.prepareShare,
                            container.sharingSettings,
                        )
                    },
                    onOpenMedia = { onOpenMedia(it, null) },
                    onOpenSettings = onOpenSettings,
                    onOpenMap = onOpenMap,
                )
                TopLevelDestination.ALBUMS -> AlbumsScreen(
                    viewModel { AlbumsViewModel(container.getTimelinePage, container.manageCollections) },
                    onOpen = onOpenList,
                    onOpenSettings = onOpenSettings,
                )
                TopLevelDestination.LIBRARY -> LibraryScreen(
                    viewModel { LibraryViewModel(container.browseLibrary, container.manageCollections, container.reconcileIdentity) },
                    folderViewModel = { folder ->
                        viewModel(key = "folder:${Route.list(MediaList.Folder(folder))}") {
                            FolderViewModel(container.getTimelinePage, container.browseLibrary, folder)
                        }
                    },
                    onOpenMedia = { assetId, folder -> onOpenMedia(assetId, MediaList.Folder(folder)) },
                    onOpenTag = { onOpenList(MediaList.Tag(it)) },
                    onOpenSettings = onOpenSettings,
                    onOpenCandidates = onOpenCandidates,
                    onOpenTrash = onOpenTrash,
                    onOpenMap = onOpenMap,
                )
                TopLevelDestination.SEARCH -> SearchScreen(
                    viewModel { SearchViewModel(container.searchMedia, container.manageCollections) },
                    onOpenMedia = { onOpenMedia(it, null) },
                    onPlayAt = onPlayAt,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }

    @Composable
    private fun FullScreen(route: String, open: (String) -> Unit, onBack: () -> Unit) {
        when {
            route == Route.SETTINGS -> SettingsScreen(
                viewModel {
                    SettingsViewModel(
                        container.themeColorSettings, container.sharingSettings, container.mapSettings, container.browseLibrary,
                        container.manageModels, container::downloadModel, container.indexJobs, container.planMediaIndex,
                        container.indexSettings, container.searchByMeaning.spaces(), container.aiBackendChoice,
                    ) { IndexingService.start(applicationContext, it) }
                },
                onBack,
            )
            route == Route.IDENTITY -> IdentityScreen(viewModel { IdentityViewModel(container.reconcileIdentity) }, onBack)
            route == Route.TRASH -> TrashScreen(viewModel { TrashViewModel(container.browseTrash, container::syncCatalog) }, onBack)
            route == Route.MAP -> MapScreen(
                viewModel { MapViewModel(container.browseMap) },
                onOpenArea = { open(Route.list(MediaList.Area(it))) },
                onBack = onBack,
            )
            Route.isSimilar(route) -> SearchScreen(
                viewModel { SearchViewModel(container.searchMedia, container.manageCollections, similarTo = Route.parseSimilar(route)) },
                onOpenMedia = { open(Route.viewer(it)) },
                onPlayAt = { assetId, ms -> open(Route.playVideoAt(assetId, ms)) },
                onOpenSettings = { open(Route.SETTINGS) },
                onBack = onBack,
            )
            Route.isList(route) -> {
                val list = Route.parseList(route)
                CollectionScreen(
                    viewModel { CollectionViewModel(container.getTimelinePage, container.manageCollections, list) },
                    onOpenMedia = { open(Route.viewer(it, list)) },
                    onBack = onBack,
                )
            }
            else -> {
                val viewer = Route.parseViewer(route)
                MediaViewerScreen(
                    viewModel {
                        val page = container.getTimelinePage
                        // From a folder, album or tag, swiping stays in it; from the timeline, it follows the timeline.
                        val query = viewer.list?.query(page.query.value) ?: page.query.value
                        ViewerViewModel(
                            page, viewer.assetId, viewer.startMs, container::newVideoPlayer, container.getMediaDetails, query,
                            container.markMedia, container.manageCollections, container.mediaFormatProbe,
                            container.browseMap, container.manageAnnotations, container.editMetadata, container.writeMetadataToFile,
                        )
                    },
                    onBack,
                    onFindSimilar = { open(Route.similar(it)) },
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
 * Routes over the shell, kept as strings so they survive in saved state. A NUL separates the parts
 * of a list, because no id or path can contain one.
 */
object Route {
    const val SHELL = "shell"
    const val SETTINGS = "settings"
    const val IDENTITY = "identity"
    const val TRASH = "trash"
    const val MAP = "map"
    private const val VIEWER = "viewer:"
    private const val LIST = "list:"
    private const val SIMILAR = "similar:"
    private const val SEP = '\u0000'

    data class Viewer(val assetId: String, val startMs: Long?, val list: MediaList?)

    /** The viewer over the timeline, or over a folder, album, tag or the favorites when [list] is given. */
    fun viewer(assetId: String, list: MediaList? = null) = VIEWER + assetId + list?.let { "$SEP${encode(it)}" }.orEmpty()

    /** PlayVideoAt (ARCHITECTURE §1.2): opens the viewer on a video and plays it from [startMs]. */
    fun playVideoAt(assetId: String, startMs: Long) = "$VIEWER$assetId@$startMs"

    /** Photos and video scenes like [assetId] (SRCH-001). */
    fun similar(assetId: String) = SIMILAR + assetId

    fun isSimilar(route: String) = route.startsWith(SIMILAR)

    fun parseSimilar(route: String) = route.removePrefix(SIMILAR)

    fun parseViewer(route: String): Viewer {
        val parts = route.removePrefix(VIEWER).split(SEP, limit = 2)
        val head = parts[0]
        val at = head.lastIndexOf('@')
        val list = parts.getOrNull(1)?.let(::decode)
        return if (at < 0) Viewer(head, null, list) else Viewer(head.substring(0, at), head.substring(at + 1).toLongOrNull(), list)
    }

    fun list(list: MediaList) = LIST + encode(list)

    fun isList(route: String) = route.startsWith(LIST)

    fun parseList(route: String): MediaList = decode(route.removePrefix(LIST))

    private fun encode(list: MediaList): String = when (list) {
        is MediaList.Folder -> "f$SEP${list.folder.volumeId}$SEP${list.folder.relativePath}"
        is MediaList.Album -> "a$SEP${list.albumId}"
        is MediaList.Tag -> "t$SEP${list.tagId}"
        MediaList.Favorites -> "v"
        is MediaList.Area -> listOf("g", list.bounds.south, list.bounds.west, list.bounds.north, list.bounds.east).joinToString("$SEP")
    }

    private fun decode(value: String): MediaList {
        if (value.startsWith("g$SEP")) {
            val (south, west, north, east) = value.split(SEP).drop(1).map { it.toDouble() }
            return MediaList.Area(GeoBounds(south, west, north, east))
        }
        val parts = value.split(SEP, limit = 3)
        return when (parts[0]) {
            "f" -> MediaList.Folder(FolderRef(parts[1], parts.getOrElse(2) { "" }))
            "a" -> MediaList.Album(parts[1])
            "t" -> MediaList.Tag(parts[1])
            else -> MediaList.Favorites
        }
    }
}
