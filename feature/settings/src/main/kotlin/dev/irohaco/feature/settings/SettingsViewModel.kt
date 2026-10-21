package dev.irohaco.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.ThemeColorSettings
import dev.irohaco.core.contracts.ShareLocationPolicy
import dev.irohaco.core.contracts.SharingSettings
import dev.irohaco.core.domain.ThemeColor
import dev.irohaco.core.domain.ThemeContrast
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: ThemeColorSettings, private val sharing: SharingSettings? = null) : ViewModel() {
    val themeColor: StateFlow<ThemeColor> =
        settings.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeColor.Device)

    val contrast: StateFlow<ThemeContrast> =
        settings.observeContrast().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeContrast.DEVICE)

    /** What to do with a shared photo's GPS by default (PRD MET-003). */
    val shareLocationPolicy: StateFlow<ShareLocationPolicy> =
        (sharing?.shareLocationPolicy ?: flowOf(ShareLocationPolicy.ASK))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShareLocationPolicy.ASK)

    fun select(color: ThemeColor) {
        viewModelScope.launch { settings.set(color) }
    }

    fun selectContrast(contrast: ThemeContrast) {
        viewModelScope.launch { settings.setContrast(contrast) }
    }

    fun selectShareLocationPolicy(policy: ShareLocationPolicy) {
        viewModelScope.launch { sharing?.setShareLocationPolicy(policy) }
    }
}
