package dev.irohaco.core.application

import dev.irohaco.core.contracts.AppearanceSettings
import dev.irohaco.core.domain.ThemeColor
import kotlinx.coroutines.flow.Flow

class ThemeColorSettings(private val settings: AppearanceSettings) {
    fun observe(): Flow<ThemeColor> = settings.themeColor

    suspend fun set(color: ThemeColor) = settings.setThemeColor(color)
}
