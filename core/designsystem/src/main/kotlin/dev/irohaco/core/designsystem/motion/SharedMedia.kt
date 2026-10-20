package dev.irohaco.core.designsystem.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** The scopes a grid-to-viewer shared element transition needs (DESIGN §2.4). */
class SharedMediaScope(val transition: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

/** Provided by the screen that switches between the grid and the viewer; absent in previews and tests. */
val LocalSharedMediaScope = staticCompositionLocalOf<SharedMediaScope?> { null }

/** Marks the same photo or video on both sides of the transition; does nothing without a scope. */
@Composable
fun Modifier.sharedMedia(assetId: String): Modifier {
    val scope = LocalSharedMediaScope.current ?: return this
    return with(scope.transition) {
        this@sharedMedia.sharedElement(rememberSharedContentState("media-$assetId"), scope.visibility)
    }
}
