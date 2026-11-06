package dev.irohaco.infrastructure.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class DataStoreModelMarksTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a color is kept per model and taken off again`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(testScheduler).backgroundScope) {
            folder.newFile("settings.preferences_pb").also { it.delete() }
        }
        val marks = DataStoreModelMarks(store) { error("no pictures in this test") }
        assertEquals(emptyMap(), marks.marks.first())
        marks.setColor("claude", 0xFFD97757)
        marks.setColor("gemma", 0xFF1A73E8)
        assertEquals(mapOf("claude" to 0xFFD97757, "gemma" to 0xFF1A73E8), marks.marks.first().mapValues { it.value.color })
        marks.setColor("claude", null)
        assertEquals(setOf("gemma"), marks.marks.first().keys)
    }
}
