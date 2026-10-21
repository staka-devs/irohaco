package dev.irohaco

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.irohaco.infrastructure.media.AndroidTrashReader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** L2: lists MediaStore's trash and reflects a restore (GAL-015). */
@RunWith(AndroidJUnit4::class)
class TrashTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val reader = AndroidTrashReader(context)
    private val created = mutableListOf(insertTestImage(context))
    private val image get() = created.first()

    @After
    fun tearDown() {
        created.forEach { resolver.delete(it, null, null) }
    }

    @Test
    fun listsATrashedFileAndDropsItOnceRestored() = runBlocking {
        val expiresAt = (System.currentTimeMillis() / 1000) + 30 * 86_400 // MediaStore stores this in seconds
        resolver.update(
            image,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 1)
                put(MediaStore.MediaColumns.DATE_EXPIRES, expiresAt)
            },
            null,
            null,
        )

        val trashed = reader.list().firstOrNull { it.contentUri == image.toString() }
        assertTrue("expected the trashed file in the list", trashed != null)
        assertEquals(expiresAt * 1000, trashed!!.expiresAtMs)

        resolver.update(image, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
        assertTrue(reader.list().none { it.contentUri == image.toString() })
    }
}
