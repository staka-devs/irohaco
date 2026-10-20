package dev.irohaco.core.designsystem.tokens

import androidx.compose.ui.graphics.Color

/** DESIGN.md §2.1: the viewer and video player stay black with white content whatever the theme. */
object IrohacoViewerColors {
    val background: Color = Color.Black
    val content: Color = Color.White
    /** Behind overlays such as the top bar, so they stay legible on bright photos. */
    val scrim: Color = Color.Black.copy(alpha = 0.5f)
}
