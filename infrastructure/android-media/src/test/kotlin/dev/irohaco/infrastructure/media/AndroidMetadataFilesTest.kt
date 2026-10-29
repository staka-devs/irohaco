package dev.irohaco.infrastructure.media

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.MetadataField
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edits written into a JPEG's EXIF through a checked working copy, with the original restorable (ADR-0030, AC-008). */
@RunWith(RobolectricTestRunner::class)
class AndroidMetadataFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = AndroidMetadataFiles(context) { ZoneOffset.ofHours(9) }
    private val values = mapOf(
        MetadataField.TAKEN to "2025-10-29T08:30",
        MetadataField.LOCATION to "34.69,135.5",
        MetadataField.DESCRIPTION to "Pier",
    )

    // A plain file:// URI stands in for MediaStore's content:// one; ContentResolver serves both.
    private fun original(): Pair<File, String> {
        val file = File(context.filesDir, "original.jpg")
        javaClass.getResourceAsStream("/plain.jpg")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        ExifInterface(file.path).apply {
            setLatLong(35.68, 139.76)
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "40/1")
            saveAttributes()
        }
        return file to Uri.fromFile(file).toString()
    }

    private fun allowLocation() = Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)

    @Test
    fun `the values reach the original only through a checked copy, and the backup puts it back`() = runTest {
        allowLocation()
        val (file, uri) = original()
        val before = file.readBytes()
        assertTrue(files.stage("op", uri, values))
        assertContentEquals(before, file.readBytes())
        assertTrue(files.replace("op", uri))
        assertTrue(files.verify("op", uri, values))
        val exif = ExifInterface(file.path)
        assertEquals("2025:10:29 08:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("+09:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals("Pier", exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        assertEquals(34.69, exif.latLong!![0], 1e-6)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)) // it belonged to the old point
        assertTrue(sameImage(before, file.readBytes()))
        assertFalse(files.verify("op", uri, values + (MetadataField.DESCRIPTION to "Harbour")))

        assertTrue(files.restore("op", uri))
        assertContentEquals(before, file.readBytes())
        files.discard("op")
        assertFalse(files.restore("op", uri))
    }

    @Test
    fun `nothing is staged without the location permission, as the OS would hand over the file without its GPS`() = runTest {
        val (_, uri) = original()
        assertFalse(files.stage("op", uri, values))
    }

    @Test
    fun `a copy can leave out the location and the original stays as it was`() = runTest {
        allowLocation()
        val (file, uri) = original()
        val before = file.readBytes()
        val copy = assertNotNull(files.prepareCopy(uri, mapOf(MetadataField.DESCRIPTION to "Pier"), removeLocation = true))
        val exif = ExifInterface(copy.path)
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
        assertEquals("Pier", exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun `the image data is told apart from the header`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 4, 1, 2, 0xFF.toByte(), 0xDA.toByte(), 9, 9)
        assertEquals(8, startOfScan(jpeg))
        assertEquals(-1, startOfScan(byteArrayOf(1, 2, 3, 4)))
        val otherHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 9, 9)
        assertTrue(sameImage(jpeg, otherHeader))
        assertFalse(sameImage(jpeg, otherHeader.copyOf().also { it[5] = 8 }))
    }
}
