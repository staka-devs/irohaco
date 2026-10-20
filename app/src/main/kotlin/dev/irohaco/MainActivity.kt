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
                // Full-screen pages open over the shell, which keeps its saved state (tab, scroll position) meanwhile.
                var route by rememberSaveable { mutableStateOf(Route.SHELL) }
                val shellState = rememberSaveableStateHolder()
                val stores = viewModel { ScreenViewModelStores() }
                SharedTransitionLayout {
                    AnimatedContent(targetState = route, label = "route") { target ->
                        CompositionLocalProvider(LocalSharedMediaScope provides SharedMediaScope(this@SharedTransitionLayout, this)) {
                            if (target == Route.SHELL) {
                                shellState.SaveableStateProvider(Route.SHELL) {
                                    Shell(
                                        onOpenMedia = { route = Route.viewer(it) },
                                        onOpenSettings = { route = Route.SETTINGS },
                                    )
                                }
                            } else {
                                RouteScope(target, stores) {
                                    FullScreen(target, onBack = { route = Route.SHELL })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Shell(onOpenMedia: (String) -> Unit, onOpenSettings: () -> Unit) {
        IrohacoApp { destination ->
            when (destination) {
                TopLevelDestination.PHOTOS -> TimelineScreen(
                    viewModel {
                        TimelineViewModel(container.getTimelinePage, container.getLibraryAccess, container.organizeMedia, container::syncCatalog)
                    },
                    onOpenMedia = onOpenMedia,
                    onOpenSettings = onOpenSettings,
                )
                else -> DestinationPlaceholder(destination)
            }
        }
    }

    @Composable
    private fun FullScreen(route: String, onBack: () -> Unit) {
        when {
            route == Route.SETTINGS -> SettingsScreen(viewModel { SettingsViewModel(container.themeColorSettings) }, onBack)
            else -> {
                val (assetId, startMs) = Route.parseViewer(route)
                MediaViewerScreen(
                    viewModel { ViewerViewModel(container.getTimelinePage, assetId, startMs, container::newVideoPlayer, container.getMediaDetails) },
                    onBack,
                )
            }
        }
    }

    /** Gives the route its own ViewModelStore and clears it when the route closes for good (not on rotation). */
    @Composable
    private fun RouteScope(route: String, stores: ScreenViewModelStores, content: @Composable () -> Unit) {
        val owner = remember(route) { stores.owner(route) }
        DisposableEffect(route) {
            onDispose { if (!isChangingConfigurations) stores.clear(route) }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
    }
}

/** Routes over the shell, kept as strings so they survive in saved state. */
object Route {
    const val SHELL = "shell"
    const val SETTINGS = "settings"
    private const val VIEWER = "viewer:"

    fun viewer(assetId: String) = VIEWER + assetId

    /** PlayVideoAt (ARCHITECTURE §1.2): opens the viewer on a video and plays it from [startMs]. */
    fun playVideoAt(assetId: String, startMs: Long) = "$VIEWER$assetId@$startMs"

    fun parseViewer(route: String): Pair<String, Long?> {
        val body = route.removePrefix(VIEWER)
        val at = body.lastIndexOf('@')
        return if (at < 0) body to null else body.substring(0, at) to body.substring(at + 1).toLongOrNull()
    }
}
