package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.irohaco.core.contracts.IndexSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreIndexSettings(private val store: DataStore<Preferences>) : IndexSettings {
    override val autoIndex: Flow<Boolean> = store.data.map { it[AUTO_INDEX] ?: false }.distinctUntilChanged()

    override suspend fun setAutoIndex(enabled: Boolean) {
        store.edit { it[AUTO_INDEX] = enabled }
    }

    private companion object {
        val AUTO_INDEX = booleanPreferencesKey("auto_index")
    }
}
