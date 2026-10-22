package dev.irohaco

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.irohaco.core.contracts.TimelineItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Spike F (ARCHITECTURE §16): the user's marks stay on the right photo when the file is renamed,
 * moved, copied, or deleted and put back. Removable storage and writing back to the original are
 * left to the device checks and to M3.
 */
@RunWith(AndroidJUnit4::class)
class MediaStorePersistenceTest {
    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    // Its own database, so the app's catalog and the other tests are untouched.
    private val dbName = "spike-f-${UUID.randomUUID()}.db"
    private val container = AppContainer(context, dbName)
    private val created = mutableListOf<Uri>()
    private val name = "irohaco-spike-f-${UUID.randomUUID()}.png"
    private val takenMs = 1_790_000_000_000L

    @After
    fun tearDown() {
        created.forEach { resolver.delete(it, null, null) }
        container.database.close()
        context.deleteDatabase(dbName)
    }

    private fun insert(fileName: String = name, folder: String = "Pictures/IrohacoSpikeF", color: Int = 0xFF3366CC.toInt()): Uri {
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.DATE_TAKEN, takenMs)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        resolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        created += uri
        return uri
    }

    private suspend fun itemFor(uri: Uri): TimelineItem? {
        container.syncCatalog()
        val count = container.getTimelinePage.count()
        return container.getTimelinePage(0, count).firstOrNull { it.contentUri == uri.toString() }
    }

    private suspend fun tagNames(assetId: String) = container.manageCollections.tagsOf(assetId).first().map { it.name }

    private suspend fun taggedPhoto(): Pair<Uri, String> {
        val uri = insert()
        val item = assertNotNull(itemFor(uri))
        container.manageCollections.addTag(listOf(item), "spike")
        return uri to item.assetId
    }

    private fun assertNotNull(item: TimelineItem?): TimelineItem {
        assertNotNull("the file should be on the timeline after a sync", item)
        return item!!
    }

    @Test
    fun aRenamedFileKeepsItsAssetAndTags() = runBlocking {
        val (uri, assetId) = taggedPhoto()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "renamed-$name") }, null, null)

        val after = assertNotNull(itemFor(uri))
        assertEquals(assetId, after.assetId)
        assertEquals(listOf("spike"), tagNames(assetId))
    }

    @Test
    fun aMovedFileKeepsItsAssetAndTags() = runBlocking {
        val (uri, assetId) = taggedPhoto()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IrohacoSpikeF/moved") }, null, null)

        val after = assertNotNull(itemFor(uri))
        assertEquals(assetId, after.assetId)
        assertEquals(listOf("spike"), tagNames(assetId))
    }

    @Test
    fun aCopyIsAnotherAssetAndTheOriginalKeepsItsTags() = runBlocking {
        val (_, assetId) = taggedPhoto()
        val copy = insert(folder = "Pictures/IrohacoSpikeF/copy")

        val copied = assertNotNull(itemFor(copy))
        assertNotEquals(assetId, copied.assetId)
        assertEquals(emptyList<String>(), tagNames(copied.assetId))
        assertEquals(listOf("spike"), tagNames(assetId))
    }

    @Test
    fun aFilePutBackIsOfferedAsTheSameAndLinkingCarriesTheTags() = runBlocking {
        val (uri, assetId) = taggedPhoto()
        // Like a move to another volume: the old row goes away and a new one appears with the same name and time.
        resolver.delete(uri, null, null)
        created -= uri
        container.syncCatalog()
        val back = insert()
        container.syncCatalog()

        val candidate = container.reconcileIdentity.pending().first().singleOrNull { it.missingAssetId == assetId }
        assertNotNull("the new file should be offered as the missing one", candidate)
        container.reconcileIdentity.sameFile(candidate!!)

        val linked = assertNotNull(itemFor(back))
        assertEquals(assetId, linked.assetId)
        assertEquals(listOf("spike"), tagNames(linked.assetId))
    }
}
