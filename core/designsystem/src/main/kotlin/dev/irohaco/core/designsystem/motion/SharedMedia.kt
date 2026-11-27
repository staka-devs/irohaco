package dev.irohaco.core.designsystem.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The scopes a grid-to-viewer shared element transition needs (DESIGN §2.4).
 *
 * @param active the photo or video that moves: the one just opened, or the one the viewer shows. Only it is a shared
 *   element; marking every grid cell made each new row register and match an element, half the time of a scrolled frame.
 */
class SharedMediaScope(val transition: SharedTransitionScope, val visibility: AnimatedVisibilityScope, val active: MutableState<String?>)

/** Provided by the screen that switches between the grid and the viewer; absent in previews and tests. */
val LocalSharedMediaScope = staticCompositionLocalOf<SharedMediaScope?> { null }

/** Marks the same photo or video on both sides of the transition; does nothing without a scope, for any other file, or with animations off. */
@Composable
fun Modifier.sharedMedia(assetId: String): Modifier {
    val scope = LocalSharedMediaScope.current ?: return this
    if (scope.active.value != assetId || rememberReducedMotion()) return this
    return with(scope.transition) {
        this@sharedMedia.sharedElement(rememberSharedContentState("media-$assetId"), scope.visibility)
    }
}
