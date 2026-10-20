package dev.irohaco.core.contracts

import dev.irohaco.core.domain.ThemeColor
import dev.irohaco.core.domain.ThemeContrast
import kotlinx.coroutines.flow.Flow

/** Non-sensitive app settings kept on the device (ARCHITECTURE §9.2: not the API keys). */
interface AppearanceSettings {
    val themeColor: Flow<ThemeColor>

    val themeContrast: Flow<ThemeContrast>

    suspend fun setThemeColor(color: ThemeColor)

    suspend fun setThemeContrast(contrast: ThemeContrast)
}

/** Folders the user hid from the timeline (GAL-002). They stay in the library, where the user can show them again. */
interface LibrarySettings {
    val excludedFolders: Flow<Set<FolderRef>>

    suspend fun setExcluded(folder: FolderRef, excluded: Boolean)
}
