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

/**
 * DESIGN.md §1, §2.1: the only theme feature code may use. It wraps [MaterialExpressiveTheme], so
 * shapes, typography and motion keep the library's M3 Expressive defaults (`MotionScheme.expressive()`).
 *
 * @param seedColor a colour the user picked in settings; null follows the device's dynamic colour
 *   (Material You, from the wallpaper).
 * @param dynamicColor false ignores the device colour and uses [DefaultSeedColor] when no seed is given.
 */
@Composable
fun IrohacoTheme(
    seedColor: Color? = null,
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        seedColor == null && dynamicColor ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> seededScheme(seedColor ?: DefaultSeedColor, darkTheme, context)
    }
    MaterialExpressiveTheme(colorScheme = colorScheme, content = content)
}

/** A tonal scheme from the seed that also follows the device's contrast setting (standard, medium, high). */
@Composable
private fun seededScheme(seed: Color, darkTheme: Boolean, context: Context): ColorScheme {
    val contrast = deviceContrast(context)
    return remember(seed, darkTheme, contrast) { dynamicColorScheme(seedColor = seed, isDark = darkTheme, contrastLevel = contrast) }
}

/** -1..1 from the system contrast setting (Android 14+); 0 is standard. */
private fun deviceContrast(context: Context): Double =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(UiModeManager::class.java)?.contrast?.toDouble() ?: 0.0
    } else {
        0.0
    }
