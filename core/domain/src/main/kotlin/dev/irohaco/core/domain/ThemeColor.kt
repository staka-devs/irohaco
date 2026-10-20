package dev.irohaco.core.domain

/** Where the app's colour scheme comes from (DESIGN §2.1). */
sealed interface ThemeColor {
    /** The device's dynamic colour, taken from the wallpaper (Material You). The default. */
    data object Device : ThemeColor

    /** A scheme generated from a colour the user picked, as 0xRRGGBB. */
    data class Seed(val rgb: Int) : ThemeColor {
        init {
            require(rgb in 0..0xFFFFFF) { "rgb must be 0xRRGGBB" }
        }
    }
}
