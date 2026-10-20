package dev.irohaco.core.designsystem.tokens

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** One shade of a hue: "50" to "900", or the accents "A100" to "A700". */
class PaletteShade(val label: String, val color: Color)

class PaletteHue(@StringRes val name: Int, val shades: List<PaletteShade>) {
    /** The 500 shade, shown when the hues are listed. */
    val main: Color get() = shades.first { it.label == "500" }.color
}

/** The full Material Design colour palette offered as theme colours (DESIGN §2.1). */
val MaterialPalette: List<PaletteHue> get() = MaterialPaletteData

/** Black or white, whichever stays legible on a swatch of [background]. */
fun legibleContentOn(background: Color): Color = if (background.luminance() > 0.5f) Color.Black else Color.White
