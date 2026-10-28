package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AiSettings
import dev.irohaco.core.contracts.GpuCheck
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreAiSettings(private val store: DataStore<Preferences>) : AiSettings {
    override val backend: Flow<AiBackend?> =
        store.data.map { prefs -> prefs[BACKEND]?.let { name -> AiBackend.entries.firstOrNull { it.name == name } } }.distinctUntilChanged()

    override suspend fun setBackend(backend: AiBackend) {
        store.edit { it[BACKEND] = backend.name }
    }

    override val gpuCheck: Flow<GpuCheck?> = store.data.map { prefs ->
        val path = prefs[GPU_MODEL] ?: return@map null
        GpuCheck(path, prefs[GPU_WORKS] ?: false)
    }.distinctUntilChanged()

    override suspend fun setGpuCheck(check: GpuCheck) {
        store.edit {
            it[GPU_MODEL] = check.modelPath
            it[GPU_WORKS] = check.works
        }
    }

    private companion object {
        val BACKEND = stringPreferencesKey("ai_backend")
        val GPU_MODEL = stringPreferencesKey("ai_gpu_checked_model")
        val GPU_WORKS = booleanPreferencesKey("ai_gpu_works")
    }
}
