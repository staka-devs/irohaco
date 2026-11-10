package dev.irohaco.ui

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

/**
 * Holds the app's content and caps the "content changed" events it passes on to accessibility services.
 *
 * Compose sends one such event per changed cell. When a pinch shows the densest grid, some 1,700 new cells send
 * theirs at once; with accessibility services on, that overflowed binder and the system killed the app. Past
 * [LIMIT] events in a [WINDOW_MS] window, the rest become one event for the whole content, which tells services
 * to read it again, so nothing they need is lost.
 */
class AccessibilityEventLimiter(context: Context) : FrameLayout(context) {
    private var windowStart = 0L
    private var sent = 0
    private var pending = false

    override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return super.requestSendAccessibilityEvent(child, event)
        val now = SystemClock.uptimeMillis()
        if (now - windowStart >= WINDOW_MS) {
            windowStart = now
            sent = 0
        }
        if (sent < LIMIT) {
            sent++
            return super.requestSendAccessibilityEvent(child, event)
        }
        if (!pending) {
            pending = true
            postDelayed({
                pending = false
                parent?.notifySubtreeAccessibilityStateChanged(this, this, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
            }, WINDOW_MS)
        }
        return false
    }

    private companion object {
        const val WINDOW_MS = 100L
        const val LIMIT = 64
    }
}
