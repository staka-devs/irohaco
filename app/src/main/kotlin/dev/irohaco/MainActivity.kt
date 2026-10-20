package dev.irohaco

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.feature.timeline.TimelineScreen
import dev.irohaco.feature.timeline.TimelineViewModel
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
                IrohacoApp { destination ->
                    when (destination) {
                        TopLevelDestination.PHOTOS -> TimelineScreen(
                            viewModel { TimelineViewModel(container.getTimelinePage, container.getLibraryAccess) },
                        )
                        else -> DestinationPlaceholder(destination)
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
