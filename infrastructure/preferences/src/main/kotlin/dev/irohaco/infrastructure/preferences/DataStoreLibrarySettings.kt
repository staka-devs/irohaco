package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.irohaco.core.contracts.LibraryLayout
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.LibrarySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreLibrarySettings(private val store: DataStore<Preferences>) : LibrarySettings {
    override val excludedFolders: Flow<Set<FolderRef>> =
        store.data.map { prefs -> prefs[EXCLUDED].orEmpty().mapNotNull(::decode).toSet() }.distinctUntilChanged()

    override suspend fun setExcluded(folder: FolderRef, excluded: Boolean) {
        store.edit { prefs ->
            val current = prefs[EXCLUDED].orEmpty()
            prefs[EXCLUDED] = if (excluded) current + encode(folder) else current - encode(folder)
        }
    }

    // A set has no order, so the pinned folders are one string joined with a separator.
    override val pinnedFolders: Flow<List<FolderRef>> =
        store.data.map { prefs -> prefs[PINNED].orEmpty().split(LIST_SEP).filter { it.isNotEmpty() }.mapNotNull(::decode) }
            .distinctUntilChanged()

    override suspend fun setPinnedFolders(folders: List<FolderRef>) {
        store.edit { it[PINNED] = folders.distinct().joinToString(LIST_SEP.toString(), transform = ::encode) }
    }

    override val libraryLayout: Flow<LibraryLayout> =
        store.data.map { prefs -> LibraryLayout.entries.firstOrNull { it.name == prefs[LAYOUT] } ?: LibraryLayout.TREE }
            .distinctUntilChanged()

    override suspend fun setLibraryLayout(layout: LibraryLayout) {
        store.edit { it[LAYOUT] = layout.name }
    }

    internal companion object {
        val EXCLUDED = stringSetPreferencesKey("excluded_folders")
        val PINNED = stringPreferencesKey("pinned_folders")
        val LAYOUT = stringPreferencesKey("library_layout")
        private const val LIST_SEP = '\u0001'

        // A NUL cannot appear in a volume id or a path, so it separates the two safely.
        fun encode(folder: FolderRef) = "${folder.volumeId}\u0000${folder.relativePath}"

        fun decode(value: String): FolderRef? =
            value.split('\u0000', limit = 2).takeIf { it.size == 2 }?.let { FolderRef(it[0], it[1]) }
    }
}
