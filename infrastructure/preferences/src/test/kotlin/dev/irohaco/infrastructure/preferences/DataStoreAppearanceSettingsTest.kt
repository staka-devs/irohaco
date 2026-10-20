package dev.irohaco.infrastructure.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.irohaco.core.domain.ThemeColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class DataStoreAppearanceSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `defaults to the device colour and keeps a picked seed`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(testScheduler).backgroundScope) {
            folder.newFile("settings.preferences_pb").also { it.delete() }
        }
        val settings = DataStoreAppearanceSettings(store)
        assertEquals(ThemeColor.Device, settings.themeColor.first())
        settings.setThemeColor(ThemeColor.Seed(0x2196F3))
        assertEquals(ThemeColor.Seed(0x2196F3), settings.themeColor.first())
        settings.setThemeColor(ThemeColor.Device)
        assertEquals(ThemeColor.Device, settings.themeColor.first())
    }

    @Test
    fun `unreadable values fall back to the device colour`() {
        assertEquals(ThemeColor.Device, DataStoreAppearanceSettings.decode("#12"))
        assertEquals(ThemeColor.Device, DataStoreAppearanceSettings.decode("blue"))
        assertEquals(ThemeColor.Device, DataStoreAppearanceSettings.decode(null))
        assertEquals("#2196F3", DataStoreAppearanceSettings.encode(ThemeColor.Seed(0x2196F3)))
    }
}
