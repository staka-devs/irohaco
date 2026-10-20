package dev.irohaco.core.application

import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.contracts.LibraryReader
import dev.irohaco.core.contracts.LibrarySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The library tab: folders per volume and the folders hidden from the timeline (PRD GAL-002). */
class BrowseLibrary(private val reader: LibraryReader, private val settings: LibrarySettings) {
    /** Re-read whenever the catalog changes, so an unplugged card shows as offline at once. */
    fun folders(): Flow<List<LibraryFolder>> = reader.changes().map { reader.folders() }

    fun excludedFolders(): Flow<Set<FolderRef>> = settings.excludedFolders

    suspend fun setExcluded(folder: FolderRef, excluded: Boolean) = settings.setExcluded(folder, excluded)
}
