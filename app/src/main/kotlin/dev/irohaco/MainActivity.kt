package dev.irohaco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.ui.IrohacoApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IrohacoTheme {
                IrohacoApp()
            }
        }
    }
}
