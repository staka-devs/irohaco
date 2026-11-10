package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.irohaco.core.contracts.ModelParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** One preference per model, its values as a JSON object, so setting them needs no change to the database. */
class DataStoreModelParameters(private val store: DataStore<Preferences>) : ModelParameters {
    override val parameters: Flow<Map<String, Map<String, String>>> = store.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val id = key.name.removePrefix(PREFIX).takeIf { it != key.name } ?: return@mapNotNull null
            val json = runCatching { JSONObject(value as String) }.getOrNull() ?: return@mapNotNull null
            id to json.keys().asSequence().associateWith(json::getString)
        }.toMap()
    }.distinctUntilChanged()

    override suspend fun set(profileId: String, values: Map<String, String>) {
        val key = stringPreferencesKey(PREFIX + profileId)
        store.edit { if (values.isEmpty()) it.remove(key) else it[key] = JSONObject(values).toString() }
    }

    private companion object {
        const val PREFIX = "model_parameters_"
    }
}
