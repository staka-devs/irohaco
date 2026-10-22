package dev.irohaco.infrastructure.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class DataStoreMapSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the online map is off by default, and turning it on and off is kept`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(testScheduler).backgroundScope) {
            folder.newFile("settings.preferences_pb").also { it.delete() }
        }
        val settings = DataStoreMapSettings(store)
        assertEquals(false, settings.allowed.first())
        settings.setAllowed(true)
        assertEquals(true, settings.allowed.first())
        settings.setAllowed(false)
        assertEquals(false, settings.allowed.first())
    }

    @Test
    fun `a consent given to OpenFreeMap before stays given`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(testScheduler).backgroundScope) {
            folder.newFile("settings.preferences_pb").also { it.delete() }
        }
        store.edit { it[stringSetPreferencesKey("map_consent")] = setOf("OPEN_FREE_MAP", "GOOGLE") }
        assertEquals(true, DataStoreMapSettings(store).allowed.first())
    }
}
