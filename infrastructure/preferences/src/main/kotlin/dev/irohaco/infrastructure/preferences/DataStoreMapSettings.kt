package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.irohaco.core.contracts.MapProvider
import dev.irohaco.core.contracts.MapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The map's provider and the user's consent per provider (ADR-0011); no consent until asked. */
class DataStoreMapSettings(private val store: DataStore<Preferences>) : MapSettings {
    override val provider: Flow<MapProvider> =
        store.data.map { prefs -> MapProvider.entries.firstOrNull { it.name == prefs[PROVIDER] } ?: MapProvider.OPEN_FREE_MAP }
            .distinctUntilChanged()

    override suspend fun setProvider(provider: MapProvider) {
        store.edit { it[PROVIDER] = provider.name }
    }

    override val consented: Flow<Set<MapProvider>> =
        store.data.map { prefs -> prefs[CONSENT].orEmpty().mapNotNull { name -> MapProvider.entries.firstOrNull { it.name == name } }.toSet() }
            .distinctUntilChanged()

    override suspend fun setConsent(provider: MapProvider, granted: Boolean) {
        store.edit { prefs ->
            val current = prefs[CONSENT].orEmpty()
            prefs[CONSENT] = if (granted) current + provider.name else current - provider.name
        }
    }

    private companion object {
        val PROVIDER = stringPreferencesKey("map_provider")
        val CONSENT = stringSetPreferencesKey("map_consent")
    }
}
