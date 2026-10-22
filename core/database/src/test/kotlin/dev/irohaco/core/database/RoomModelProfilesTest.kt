package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.ModelArtifact
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Installed models are recorded under one local connection, and a new file updates the row (M2-02). */
@RunWith(RobolectricTestRunner::class)
class RoomModelProfilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    @Test
    fun `registers a model and updates it when a new file is installed`() = runTest {
        val profiles = RoomModelProfiles(db)
        profiles.register(ModelArtifact("m", "Model", "https://example.invalid/m", 1, "first"))
        profiles.register(ModelArtifact("m", "Model", "https://example.invalid/m", 1, "second"))
        assertEquals("second", db.modelProfileDao().revision("m"))
        val rows = db.openHelper.readableDatabase.query("SELECT count(*) FROM model_profiles").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, rows)
    }
}
