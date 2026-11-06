package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.irohaco.core.contracts.ConnectionNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One preference per named connection, so naming one needs no change to the database. */
class DataStoreConnectionNames(private val store: DataStore<Preferences>) : ConnectionNames {
    override val names: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) -> key.name.removePrefix(PREFIX).takeIf { it != key.name }?.let { it to value as String } }.toMap()
    }.distinctUntilChanged()

    override suspend fun setName(id: String, name: String?) {
        val key = stringPreferencesKey(PREFIX + id)
        store.edit { if (name.isNullOrBlank()) it.remove(key) else it[key] = name.trim() }
    }

    private companion object {
        const val PREFIX = "connection_name_"
    }
}
