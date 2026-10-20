package dev.irohaco

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.irohaco.core.designsystem.motion.LocalSharedMediaScope
import dev.irohaco.core.designsystem.motion.SharedMediaScope
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.feature.timeline.TimelineScreen
import dev.irohaco.feature.timeline.TimelineViewModel
import dev.irohaco.feature.timeline.viewer.MediaViewerScreen
import dev.irohaco.feature.timeline.viewer.ViewerViewModel
import dev.irohaco.ui.DestinationPlaceholder
import dev.irohaco.ui.IrohacoApp
import dev.irohaco.ui.TopLevelDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val container get() = (application as IrohacoApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Resume covers launch, returning from the permission dialog and returning from system settings.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { syncCatalog() }
        }
        setContent {
            IrohacoTheme {
                // The viewer opens over the shell; the shell keeps its saved state (tab, scroll position) meanwhile.
                var viewing by rememberSaveable { mutableStateOf<String?>(null) }
                val screens = rememberSaveableStateHolder()
                SharedTransitionLayout {
                    AnimatedContent(targetState = viewing, label = "viewer") { assetId ->
                        CompositionLocalProvider(LocalSharedMediaScope provides SharedMediaScope(this@SharedTransitionLayout, this)) {
                            if (assetId == null) {
                                screens.SaveableStateProvider("shell") {
                                    IrohacoApp { destination ->
                                        when (destination) {
                                            TopLevelDestination.PHOTOS -> TimelineScreen(
                                                viewModel { TimelineViewModel(container.getTimelinePage, container.getLibraryAccess) },
                                                onOpenMedia = { viewing = it },
                                            )
                                            else -> DestinationPlaceholder(destination)
                                        }
                                    }
                                }
                            } else {
                                MediaViewerScreen(
                                    viewModel(key = "viewer-$assetId") { ViewerViewModel(container.getTimelinePage, assetId) },
                                    onBack = { viewing = null },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun syncCatalog() {
        try {
            withContext(Dispatchers.IO) { container.syncMediaCatalog() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed sync leaves the catalog as it was; the gallery keeps working and the next resume retries.
            Log.w("Irohaco", "Catalog sync failed", e)
        }
    }
}
