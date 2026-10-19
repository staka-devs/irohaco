package dev.irohaco.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * DESIGN.md §1: the only theme feature code may use. It wraps [MaterialExpressiveTheme], so
 * shapes, typography and motion keep the library's M3 Expressive defaults
 * (`MotionScheme.expressive()`) unless a later Issue overrides them with Irohaco-specific tokens.
 *
 * shortcut: the static (non-dynamic) scheme is M3's baseline light/dark scheme, not a scheme
 * generated from a brand seed color. DESIGN §2.1 asks for Light/Dark/Medium contrast/High
 * contrast schemes from a seed; no seed color is decided yet (PRD/DESIGN/ARCHITECTURE do not
 * name one). Replace this with the generated schemes once a brand seed is chosen.
 */
@Composable
fun IrohacoTheme(
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            darkTheme -> darkColorScheme()
            else -> lightColorScheme()
        }
    MaterialExpressiveTheme(colorScheme = colorScheme, content = content)
}
