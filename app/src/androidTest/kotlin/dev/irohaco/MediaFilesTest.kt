package dev.irohaco

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.infrastructure.media.AndroidMediaFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** L2: move, copy and rename on a file this app created, which needs no write grant (GAL-005). */
@RunWith(AndroidJUnit4::class)
class MediaFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = AndroidMediaFiles(context)
    private val created = mutableListOf(insertTestImage(context))
    private val image get() = created.first()

    @After
    fun tearDown() {
        created.forEach { context.contentResolver.delete(it, null, null) }
    }

    private fun column(uri: Uri, column: String): String? =
        context.contentResolver.query(uri, arrayOf(column), null, null, null)!!.use { if (it.moveToFirst()) it.getString(0) else null }

    @Test
    fun movesRenamesAndCopies() = runBlocking {
        val name = files.name(image.toString())!!

        assertEquals(FileOperationResult.DONE, files.move(image.toString(), "Pictures/IrohacoTestMoved/"))
        assertEquals("Pictures/IrohacoTestMoved/", column(image, MediaStore.MediaColumns.RELATIVE_PATH))
        assertTrue("Pictures/IrohacoTestMoved/" in files.folders())

        assertEquals(FileOperationResult.DONE, files.rename(image.toString(), "renamed-$name"))
        assertEquals("renamed-$name", column(image, MediaStore.MediaColumns.DISPLAY_NAME))

        assertEquals(FileOperationResult.DONE, files.copy(image.toString(), "Pictures/IrohacoTest/"))
        val copy = context.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("Pictures/IrohacoTest/", "renamed-$name"),
            null,
        )!!.use { c -> if (c.moveToFirst()) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, c.getLong(0)) else null }
        created += copy!!
        assertNotEquals(image, copy)

        // Moving the copy next to the original would collide with its name.
        assertEquals(FileOperationResult.NAME_TAKEN, files.move(copy.toString(), "Pictures/IrohacoTestMoved/"))
        context.contentResolver.delete(copy, null, null)
        created -= copy
        assertEquals(FileOperationResult.NOT_FOUND, files.rename(copy.toString(), "gone.png"))
    }
}
