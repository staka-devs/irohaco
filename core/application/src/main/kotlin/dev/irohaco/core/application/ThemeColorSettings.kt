package dev.irohaco.core.application

import dev.irohaco.core.contracts.AppearanceSettings
import dev.irohaco.core.domain.ThemeColor
import dev.irohaco.core.domain.ThemeContrast
import kotlinx.coroutines.flow.Flow

class ThemeColorSettings(private val settings: AppearanceSettings) {
    fun observe(): Flow<ThemeColor> = settings.themeColor

    fun observeContrast(): Flow<ThemeContrast> = settings.themeContrast

    suspend fun set(color: ThemeColor) = settings.setThemeColor(color)

    suspend fun setContrast(contrast: ThemeContrast) = settings.setThemeContrast(contrast)
}
