package dev.irohaco

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.irohaco.core.database.IrohacoDatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** L2: the real MediaStore, permission and Room adapters behind SyncMediaCatalog. */
@RunWith(AndroidJUnit4::class)
class CatalogSyncTest {
    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val container = AppContainer(context)

    @After
    fun tearDown() {
        container.database.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    @Test
    fun importsANewImageAndMarksItMissingAfterItIsDeleted() = runTest {
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
        val uri = insertImage()
        val id = ContentUris.parseId(uri)
        try {
            container.syncMediaCatalog()
            assertEquals("AVAILABLE", accessStateOf(id))
        } finally {
            resolver.delete(uri, null, null)
        }
        container.syncMediaCatalog()
        assertEquals("MISSING_CANDIDATE", accessStateOf(id))
    }

    private fun insertImage() = resolver.insert(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "irohaco-l2-${UUID.randomUUID()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IrohacoTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        },
    )!!.also { uri ->
        resolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun accessStateOf(mediaStoreId: Long): String =
        container.database.openHelper.readableDatabase
            .query("SELECT access_state FROM media_sources WHERE media_store_id = ?", arrayOf<Any>(mediaStoreId))
            .use { it.moveToFirst(); it.getString(0) }
}
