package dev.irohaco.core.designsystem.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.irohaco.core.designsystem.preview.IrohacoPreviews

/** Confirms IrohacoTheme wires MaterialExpressiveTheme's scheme, shapes, typography and motion. */
@IrohacoPreviews
@Composable
private fun IrohacoThemePreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text("Irohaco", style = MaterialTheme.typography.headlineLarge)
            }
        }
    }
}
