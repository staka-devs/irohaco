package dev.irohaco.feature.timeline

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Who reads the grid's cells, which decides when each photo gets its own accessibility node. */
internal enum class CellReaders {
    /** No accessibility service is on: nodes cost nothing to keep, as nothing is sent anywhere. */
    NONE,

    /** A screen reader is on (explore by touch, as TalkBack turns on, or a spoken or braille service). */
    SCREEN_READER,

    /**
     * Only other services, such as automation apps. With them Compose rebuilt and sent thousands of nodes on every
     * scroll of a dense grid, a third of the main thread, for services that never read the cells.
     */
    OTHER_SERVICES,
}

@Composable
internal fun rememberCellReaders(): CellReaders {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var readers by remember(manager) { mutableStateOf(manager.cellReaders()) }
    DisposableEffect(manager) {
        val state = AccessibilityManager.AccessibilityStateChangeListener { readers = manager.cellReaders() }
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { readers = manager.cellReaders() }
        val services = AccessibilityManager.AccessibilityServicesStateChangeListener { readers = manager.cellReaders() }
        manager.addAccessibilityStateChangeListener(state)
        manager.addTouchExplorationStateChangeListener(touch)
        manager.addAccessibilityServicesStateChangeListener(services)
        onDispose {
            manager.removeAccessibilityStateChangeListener(state)
            manager.removeTouchExplorationStateChangeListener(touch)
            manager.removeAccessibilityServicesStateChangeListener(services)
        }
    }
    return readers
}

private fun AccessibilityManager.cellReaders(): CellReaders = when {
    isTouchExplorationEnabled ||
        getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_BRAILLE).isNotEmpty() ->
        CellReaders.SCREEN_READER
    isEnabled -> CellReaders.OTHER_SERVICES
    else -> CellReaders.NONE
}
