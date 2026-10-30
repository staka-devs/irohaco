package dev.irohaco.infrastructure.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.irohaco.core.contracts.DateGrouping
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class DataStoreTimelineSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `days by default, keeps the chosen unit, the hint shown once and the columns`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(testScheduler).backgroundScope) {
            folder.newFile("settings.preferences_pb").also { it.delete() }
        }
        val settings = DataStoreTimelineSettings(store)
        assertEquals(DateGrouping.DAY, settings.dateGrouping.first())
        settings.setDateGrouping(DateGrouping.MONTH)
        assertEquals(DateGrouping.MONTH, settings.dateGrouping.first())
        assertEquals(false, settings.pinchHintShown.first())
        settings.setPinchHintShown()
        assertEquals(true, settings.pinchHintShown.first())
        assertEquals(null, settings.timelineColumns.first())
        settings.setTimelineColumns(14)
        assertEquals(14, settings.timelineColumns.first())
    }
}
