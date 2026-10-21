package dev.irohaco.infrastructure.media

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A shared copy with or without GPS, the original file left exactly as it was (PRD GAL-005, MET-003).
 * Sharing the plain content URI would have its location redacted by the OS anyway, so even "keep"
 * goes through a copy; here it is read via the unredacted original, which needs the permission.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidShareFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // FileProvider caches its parsed roots per authority in a static field, which survives across test
    // methods in the same Robolectric run while each test gets its own fresh sandboxed cache directory.
    @Before
    fun resetFileProviderRootCache() {
        val field = FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as? MutableMap<*, *>)?.clear()
    }

    // A plain file:// URI stands in for the real content:// one MediaStore would give; ContentResolver
    // serves either without needing a registered provider, which is all this test cares about.
    private fun originalWithGps(): Pair<File, String> {
        val file = File(context.filesDir, "original.jpg")
        javaClass.getResourceAsStream("/plain.jpg")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        ExifInterface(file.path).apply {
            setLatLong(35.5, 139.75)
            saveAttributes()
        }
        return file to Uri.fromFile(file).toString()
    }

    private fun grantLocationPermission() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    @Test
    fun `stripping removes the GPS from the copy, and never touches the original`() = runTest {
        val (original, contentUri) = originalWithGps()
        val sharedUri = AndroidShareFiles(context).prepareCopy(contentUri, keepLocation = false)
        assertNotNull(sharedUri)

        val sharedExif = ExifInterface(resolveFile(sharedUri).path)
        assertNull(sharedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(sharedExif.latLong)

        val originalExif = ExifInterface(original.path)
        assertEquals(35.5, originalExif.latLong!![0], 1e-4)
        assertEquals(139.75, originalExif.latLong!![1], 1e-4)
        assertTrue(resolveFile(sharedUri).path != original.path)
    }

    @Test
    fun `keeping the location preserves it in the copy, when the permission is granted`() = runTest {
        grantLocationPermission()
        val (_, contentUri) = originalWithGps()
        val sharedUri = AndroidShareFiles(context).prepareCopy(contentUri, keepLocation = true)
        val sharedExif = ExifInterface(resolveFile(assertNotNull(sharedUri)).path)
        assertEquals(35.5, sharedExif.latLong!![0], 1e-4)
        assertEquals(139.75, sharedExif.latLong!![1], 1e-4)
    }

    @Test
    fun `cleans up copies past the retention window, keeping recent ones`() = runTest {
        val shareFiles = AndroidShareFiles(context)
        val (_, contentUri) = originalWithGps()
        val keptUri = shareFiles.prepareCopy(contentUri, keepLocation = false)!!
        val staleUri = shareFiles.prepareCopy(contentUri, keepLocation = false)!!
        val staleFile = resolveFile(staleUri)
        staleFile.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1_000L) // 2h old: past the 1h retention

        shareFiles.cleanUp()

        assertTrue(resolveFile(keptUri).exists())
        assertTrue(!staleFile.exists())
    }

    private fun resolveFile(contentUri: String): File {
        // The share cache is private to this app, so a direct path lookup stands in for a resolver round-trip.
        val name = Uri.parse(contentUri).lastPathSegment!!.substringAfterLast('/')
        return File(File(context.cacheDir, "share"), name)
    }
}
