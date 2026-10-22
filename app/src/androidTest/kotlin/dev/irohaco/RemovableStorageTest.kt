package dev.irohaco

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.irohaco.core.contracts.TimelineItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Spike F (ARCHITECTURE §16), removable storage: a photo on an SD card keeps its asset and tags when
 * the card is taken out and put back. The emulator's virtual disk stands in for the card; the test is
 * skipped where the image has no virtual disk support.
 */
@RunWith(AndroidJUnit4::class)
class RemovableStorageTest {
    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val dbName = "spike-f-sd-${UUID.randomUUID()}.db"
    private val container = AppContainer(context, dbName)
    private val name = "irohaco-spike-f-sd-${UUID.randomUUID()}.jpg"
    private var volumeId: String? = null // the storage manager's id, such as public:7,1
    private var volumeName: String? = null // MediaStore's name for it, such as 1a2b-3c4d
    private var photo: Uri? = null

    private fun sh(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun waitFor(seconds: Int, check: () -> Boolean): Boolean {
        repeat(seconds * 2) {
            if (check()) return true
            Thread.sleep(500)
        }
        return check()
    }

    private fun mountedPublicVolume(): Pair<String, String>? =
        sh("sm list-volumes public").lines().map { it.trim().split(" ") }
            .firstOrNull { it.size >= 3 && it[1] == "mounted" && it[2] != "null" }
            ?.let { it[0] to it[2].lowercase() }

    private fun mediaStoreSees(name: String) = name in MediaStore.getExternalVolumeNames(context)

    // Turning the virtual disk on or off resets every volume, the built-in storage too, on some versions.
    private fun waitForBuiltInStorage() {
        waitFor(60) {
            val primary = context.getSystemService(StorageManager::class.java).primaryStorageVolume
            // MediaStore lists the volume a moment before files can be written to it again.
            primary.state == Environment.MEDIA_MOUNTED && primary.directory?.canWrite() != false &&
                mediaStoreSees(MediaStore.VOLUME_EXTERNAL_PRIMARY) &&
                runCatching { resolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), null, null, null)?.close() }.isSuccess
        }
        Thread.sleep(2_000) // the media scanner reattaches the volume's path just after it reports mounted
    }

    @Before
    fun insertCard() {
        sh("sm set-virtual-disk true")
        waitForBuiltInStorage()
        val disk = (1..20).firstNotNullOfOrNull {
            sh("sm list-disks adoptable").lines().firstOrNull { it.startsWith("disk:") }?.trim() ?: run { Thread.sleep(500); null }
        }
        assumeTrue("this emulator image has no virtual disk", disk != null)
        if (mountedPublicVolume() == null) sh("sm partition $disk public")
        assumeTrue("the virtual disk did not mount", waitFor(30) { mountedPublicVolume() != null })
        val (id, mediaName) = mountedPublicVolume()!!
        volumeId = id
        volumeName = mediaName
        assumeTrue("MediaStore does not list the card", waitFor(30) { mediaStoreSees(mediaName) })
        Log.i("SpikeF", "virtual card $id is MediaStore volume $mediaName")
    }

    @After
    fun removeCard() {
        photo?.let { runCatching { resolver.delete(it, null, null) } }
        sh("sm set-virtual-disk false")
        // Later tests sync the built-in storage; it must be back before they start.
        waitForBuiltInStorage()
        container.database.close()
        context.deleteDatabase(dbName)
    }

    private fun insertPhoto(volume: String): Uri {
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(volume),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IrohacoSD")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        val file = File.createTempFile("spike-f-sd", ".jpg", context.cacheDir)
        file.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.path).apply {
            val time = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, time.format(Date(1_790_000_000_000L)))
            saveAttributes()
        }
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        file.delete()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private suspend fun onTimeline(match: (String) -> Boolean) = run {
        container.syncCatalog()
        container.getTimelinePage(0, container.getTimelinePage.count()).firstOrNull { match(it.contentUri) }
    }

    private suspend fun tagNames(assetId: String) = container.manageCollections.tagsOf(assetId).first().map { it.name }

    @Test
    fun aPhotoOnTheCardKeepsItsAssetAndTagsWhenTheCardComesBack() = runBlocking {
        val volume = volumeName!!
        val uri = insertPhoto(volume).also { photo = it }
        val item = onTimeline { it == uri.toString() }
        assertNotNull("the photo on the card should be on the timeline", item)
        container.manageCollections.addTag(listOf(item!!), "sd")

        sh("sm unmount $volumeId")
        assertEquals("the card should go away", true, waitFor(30) { !mediaStoreSees(volume) })
        assertNull("an unmounted card's photo leaves the timeline", onTimeline { it == uri.toString() })
        assertEquals("the tag is kept while the card is out", listOf("sd"), tagNames(item.assetId))

        sh("sm mount $volumeId")
        assertEquals("the card should come back", true, waitFor(30) { mediaStoreSees(volume) })
        // MediaStore may scan the card again; give it time to list the file.
        var back: TimelineItem? = null
        repeat(20) {
            if (back == null) back = onTimeline { u -> u.contains(volume) } ?: null.also { delay(1_000) }
        }
        assertNotNull("the photo should be back after the card returns", back)
        if (back!!.assetId != item.assetId) {
            // A new MediaStore id: the photo must at least be offered as the missing one.
            Log.i("SpikeF", "the card's photo came back with a new id: ${back.contentUri} (was $uri)")
            val candidate = container.reconcileIdentity.pending().first().singleOrNull { it.missingAssetId == item.assetId }
            assertNotNull("a photo back with a new id should be offered as the missing one", candidate)
            container.reconcileIdentity.sameFile(candidate!!)
        } else {
            Log.i("SpikeF", "the card's photo came back with the same id")
        }
        val linked = onTimeline { u -> u.contains(volume) }
        assertEquals(item.assetId, linked?.assetId)
        assertEquals(listOf("sd"), tagNames(item.assetId))
        photo = Uri.parse(linked!!.contentUri)
    }
}
