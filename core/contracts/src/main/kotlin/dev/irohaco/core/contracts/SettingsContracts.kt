package dev.irohaco.core.contracts

import dev.irohaco.core.domain.ThemeColor
import kotlinx.coroutines.flow.Flow

/** Non-sensitive app settings kept on the device (ARCHITECTURE §9.2: not the API keys). */
interface AppearanceSettings {
    val themeColor: Flow<ThemeColor>

    suspend fun setThemeColor(color: ThemeColor)
}
