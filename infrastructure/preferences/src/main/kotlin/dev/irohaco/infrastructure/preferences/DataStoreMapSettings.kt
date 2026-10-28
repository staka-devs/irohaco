package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.irohaco.core.contracts.MapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreMapSettings(private val store: DataStore<Preferences>) : MapSettings {
    // The key kept its earlier form, a set of providers, so a consent given before stays given.
    override val allowed: Flow<Boolean> = store.data.map { OPEN_FREE_MAP in it[CONSENT].orEmpty() }.distinctUntilChanged()

    override suspend fun setAllowed(allowed: Boolean) {
        store.edit { it[CONSENT] = if (allowed) setOf(OPEN_FREE_MAP) else emptySet() }
    }

    private companion object {
        val CONSENT = stringSetPreferencesKey("map_consent")
        const val OPEN_FREE_MAP = "OPEN_FREE_MAP"
    }
}
