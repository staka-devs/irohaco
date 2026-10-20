package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.irohaco.core.contracts.DateGrouping
import dev.irohaco.core.contracts.TimelineSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreTimelineSettings(private val store: DataStore<Preferences>) : TimelineSettings {
    override val dateGrouping: Flow<DateGrouping> =
        store.data.map { prefs -> DateGrouping.entries.firstOrNull { it.name == prefs[GROUPING] } ?: DateGrouping.DAY }
            .distinctUntilChanged()

    override suspend fun setDateGrouping(grouping: DateGrouping) {
        store.edit { it[GROUPING] = grouping.name }
    }

    override val pinchHintShown: Flow<Boolean> = store.data.map { it[PINCH_HINT] == true }.distinctUntilChanged()

    override suspend fun setPinchHintShown() {
        store.edit { it[PINCH_HINT] = true }
    }

    private companion object {
        val GROUPING = stringPreferencesKey("date_grouping")
        val PINCH_HINT = booleanPreferencesKey("pinch_hint_shown")
    }
}
