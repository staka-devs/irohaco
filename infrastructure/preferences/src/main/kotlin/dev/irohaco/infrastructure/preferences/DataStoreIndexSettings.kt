package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.irohaco.core.contracts.IndexSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreIndexSettings(private val store: DataStore<Preferences>) : IndexSettings {
    override val autoIndex: Flow<Boolean> = store.data.map { it[AUTO_INDEX] ?: false }.distinctUntilChanged()

    override suspend fun setAutoIndex(enabled: Boolean) {
        store.edit { it[AUTO_INDEX] = enabled }
    }

    override val indexFolders: Flow<Set<String>?> = store.data.map { it[FOLDERS] }.distinctUntilChanged()

    override suspend fun setIndexFolders(folders: Set<String>?) {
        store.edit { if (folders == null) it.remove(FOLDERS) else it[FOLDERS] = folders }
    }

    private companion object {
        val AUTO_INDEX = booleanPreferencesKey("auto_index")
        val FOLDERS = stringSetPreferencesKey("index_folders")
    }
}
