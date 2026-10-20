package dev.irohaco.infrastructure.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.irohaco.core.contracts.ShareLocationPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class DataStoreSharingSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `asks by default and keeps the chosen policy`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(testScheduler).backgroundScope) {
            folder.newFile("settings.preferences_pb").also { it.delete() }
        }
        val settings = DataStoreSharingSettings(store)
        assertEquals(ShareLocationPolicy.ASK, settings.shareLocationPolicy.first())
        settings.setShareLocationPolicy(ShareLocationPolicy.STRIP)
        assertEquals(ShareLocationPolicy.STRIP, settings.shareLocationPolicy.first())
    }
}
