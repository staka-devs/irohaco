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

        fun toHex(): String = "#%06X".format(rgb)

        companion object {
            /** Accepts `#RRGGBB` or `RRGGBB` in any case; anything else is null. */
            fun parseHex(text: String): Seed? {
                val digits = text.trim().removePrefix("#")
                if (digits.length != 6 || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                return Seed(digits.toInt(16))
            }
        }
    }
}

/** How strongly the scheme separates colours (DESIGN §7). DEVICE follows the system setting (Android 14+). */
enum class ThemeContrast { DEVICE, STANDARD, MEDIUM, HIGH }
