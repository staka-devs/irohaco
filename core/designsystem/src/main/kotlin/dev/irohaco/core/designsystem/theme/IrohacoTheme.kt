package dev.irohaco.core.designsystem.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamicColorScheme

/** Blue 500: the seed when no device colour is used, e.g. in previews and screenshot tests (DESIGN §2.1). */
val DefaultSeedColor = Color(0xFF2196F3)

/** Contrast levels of Material Color Utilities: standard, medium and high. */
object SchemeContrast {
    const val STANDARD = 0.0
    const val MEDIUM = 0.5
    const val HIGH = 1.0
}

/**
 * DESIGN.md §1, §2.1: the only theme feature code may use. It wraps [MaterialExpressiveTheme], so
 * shapes, typography and motion keep the library's M3 Expressive defaults (`MotionScheme.expressive()`).
 * Light and dark schemes are both generated, and [darkTheme] picks one.
 *
 * @param seedColor a colour the user picked in settings; null follows the device's dynamic colour
 *   (Material You, from the wallpaper).
 * @param contrast one of [SchemeContrast]; null follows the device's contrast setting (Android 14+).
 * @param dynamicColor false ignores the device colour and uses [DefaultSeedColor] when no seed is given.
 */
@Composable
fun IrohacoTheme(
    seedColor: Color? = null,
    contrast: Double? = null,
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // The system's dynamic scheme already follows the device's contrast setting.
        seedColor == null && dynamicColor && contrast == null ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // A chosen contrast on the device colour: regenerate from the wallpaper's primary colour.
        seedColor == null && dynamicColor ->
            seededScheme(dynamicLightColorScheme(context).primary, darkTheme, contrast!!)
        else -> seededScheme(seedColor ?: DefaultSeedColor, darkTheme, contrast ?: deviceContrast(context))
    }
    MaterialExpressiveTheme(colorScheme = colorScheme, content = content)
}

@Composable
private fun seededScheme(seed: Color, darkTheme: Boolean, contrast: Double): ColorScheme =
    remember(seed, darkTheme, contrast) { dynamicColorScheme(seedColor = seed, isDark = darkTheme, contrastLevel = contrast) }

/** -1..1 from the system contrast setting (Android 14+); 0 is standard. */
private fun deviceContrast(context: Context): Double =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(UiModeManager::class.java)?.contrast?.toDouble() ?: SchemeContrast.STANDARD
    } else {
        SchemeContrast.STANDARD
    }
