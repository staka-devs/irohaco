package dev.irohaco.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.ThemeColorSettings
import dev.irohaco.core.domain.ThemeColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: ThemeColorSettings) : ViewModel() {
    val themeColor: StateFlow<ThemeColor> =
        settings.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeColor.Device)

    fun select(color: ThemeColor) {
        viewModelScope.launch { settings.set(color) }
    }
}
