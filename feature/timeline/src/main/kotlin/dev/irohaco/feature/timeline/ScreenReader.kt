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

/**
 * Whether a screen reader is on: explore by touch, as TalkBack turns on, or a spoken or braille service.
 * Only then does the grid give each photo its own accessibility node. Any other service, such as an
 * automation app, made Compose rebuild and send thousands of nodes on every scroll of a dense grid, a
 * third of the main thread, for services that never read them.
 */
@Composable
internal fun rememberScreenReader(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var on by remember(manager) { mutableStateOf(manager.screenReader()) }
    DisposableEffect(manager) {
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { on = manager.screenReader() }
        val services = AccessibilityManager.AccessibilityServicesStateChangeListener { on = manager.screenReader() }
        manager.addTouchExplorationStateChangeListener(touch)
        manager.addAccessibilityServicesStateChangeListener(services)
        onDispose {
            manager.removeTouchExplorationStateChangeListener(touch)
            manager.removeAccessibilityServicesStateChangeListener(services)
        }
    }
    return on
}

private fun AccessibilityManager.screenReader(): Boolean = isTouchExplorationEnabled ||
    getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_BRAILLE).isNotEmpty()
