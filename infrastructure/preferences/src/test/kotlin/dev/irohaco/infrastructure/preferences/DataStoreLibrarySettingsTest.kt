package dev.irohaco.infrastructure.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.irohaco.core.contracts.FolderRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class DataStoreLibrarySettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `excludes and shows folders again, including the volume root`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(testScheduler).backgroundScope) {
            folder.newFile("settings.preferences_pb").also { it.delete() }
        }
        val settings = DataStoreLibrarySettings(store)
        val camera = FolderRef("v1", "DCIM/Camera/")
        val root = FolderRef("v2", "")
        assertEquals(emptySet(), settings.excludedFolders.first())
        settings.setExcluded(camera, true)
        settings.setExcluded(root, true)
        assertEquals(setOf(camera, root), settings.excludedFolders.first())
        settings.setExcluded(camera, false)
        assertEquals(setOf(root), settings.excludedFolders.first())
    }
}
