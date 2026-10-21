package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.irohaco.core.contracts.ShareLocationPolicy
import dev.irohaco.core.contracts.SharingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreSharingSettings(private val store: DataStore<Preferences>) : SharingSettings {
    override val shareLocationPolicy: Flow<ShareLocationPolicy> =
        store.data.map { prefs -> ShareLocationPolicy.entries.firstOrNull { it.name == prefs[POLICY] } ?: ShareLocationPolicy.ASK }
            .distinctUntilChanged()

    override suspend fun setShareLocationPolicy(policy: ShareLocationPolicy) {
        store.edit { it[POLICY] = policy.name }
    }

    private companion object {
        val POLICY = stringPreferencesKey("share_location_policy")
    }
}
