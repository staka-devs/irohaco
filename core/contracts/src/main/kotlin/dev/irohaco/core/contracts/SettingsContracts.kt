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

    /** Folders kept at the top of the library, in the user's order (GAL-011). */
    val pinnedFolders: Flow<List<FolderRef>>

    suspend fun setPinnedFolders(folders: List<FolderRef>)

    val libraryLayout: Flow<LibraryLayout>

    suspend fun setLibraryLayout(layout: LibraryLayout)
}

/** The library as a folder tree, or as cards of the top-level folders. */
enum class LibraryLayout { TREE, GRID }

/** The unit of the timeline's date headers (GAL-012). */
enum class DateGrouping { YEAR, MONTH, DAY }

interface TimelineSettings {
    val dateGrouping: Flow<DateGrouping>

    suspend fun setDateGrouping(grouping: DateGrouping)

    /** Whether the one-time hint that pinching changes the columns was shown. */
    val pinchHintShown: Flow<Boolean>

    suspend fun setPinchHintShown()

    /** The grid's columns the user last chose; null until they change it. */
    val timelineColumns: Flow<Int?>

    suspend fun setTimelineColumns(columns: Int)
}

/** What to do about a shared photo's GPS when it has one (PRD MET-003). */
enum class ShareLocationPolicy { ASK, STRIP, KEEP }

interface SharingSettings {
    val shareLocationPolicy: Flow<ShareLocationPolicy>

    suspend fun setShareLocationPolicy(policy: ShareLocationPolicy)
}
