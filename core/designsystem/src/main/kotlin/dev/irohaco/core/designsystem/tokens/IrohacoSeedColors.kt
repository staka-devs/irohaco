package dev.irohaco.core.designsystem.tokens

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.irohaco.core.designsystem.R
import dev.irohaco.core.designsystem.theme.DefaultSeedColor

class SeedColorOption(val color: Color, @StringRes val name: Int)

/** The theme colours offered in settings (Material 500 hues), Blue 500 first. */
val IrohacoSeedColors: List<SeedColorOption> = listOf(
    SeedColorOption(DefaultSeedColor, R.string.color_blue),
    SeedColorOption(Color(0xFF3F51B5), R.string.color_indigo),
    SeedColorOption(Color(0xFF9C27B0), R.string.color_purple),
    SeedColorOption(Color(0xFFE91E63), R.string.color_pink),
    SeedColorOption(Color(0xFFF44336), R.string.color_red),
    SeedColorOption(Color(0xFFFF9800), R.string.color_orange),
    SeedColorOption(Color(0xFFFFC107), R.string.color_amber),
    SeedColorOption(Color(0xFF4CAF50), R.string.color_green),
    SeedColorOption(Color(0xFF009688), R.string.color_teal),
    SeedColorOption(Color(0xFF795548), R.string.color_brown),
)

/** Black or white, whichever stays legible on a swatch of [background]. */
fun legibleContentOn(background: Color): Color = if (background.luminance() > 0.5f) Color.Black else Color.White
