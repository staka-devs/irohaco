package dev.irohaco.infrastructure.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.irohaco.core.contracts.MapProvider
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
    fun `OpenFreeMap without consent by default, consent kept per provider and revocable`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(testScheduler).backgroundScope) {
            folder.newFile("settings.preferences_pb").also { it.delete() }
        }
        val settings = DataStoreMapSettings(store)
        assertEquals(MapProvider.OPEN_FREE_MAP, settings.provider.first())
        assertEquals(emptySet(), settings.consented.first())
        settings.setConsent(MapProvider.OPEN_FREE_MAP, true)
        settings.setProvider(MapProvider.GOOGLE)
        assertEquals(MapProvider.GOOGLE, settings.provider.first())
        assertEquals(setOf(MapProvider.OPEN_FREE_MAP), settings.consented.first())
        settings.setConsent(MapProvider.OPEN_FREE_MAP, false)
        assertEquals(emptySet(), settings.consented.first())
    }
}
