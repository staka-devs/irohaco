package dev.irohaco

import android.Manifest
import android.content.ContentUris
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** L2: the real MediaStore, permission and Room adapters behind SyncMediaCatalog. */
@RunWith(AndroidJUnit4::class)
class CatalogSyncTest {
    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    // Its own file: deleting the app's database under the running app would break the other tests.
    private val container = AppContainer(context, TEST_DATABASE)

    @After
    fun tearDown() {
        container.database.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun importsANewImageAndMarksItMissingAfterItIsDeleted() = runTest {
        context.deleteDatabase(TEST_DATABASE)
        val uri = insertTestImage(context)
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

    private fun accessStateOf(mediaStoreId: Long): String =
        container.database.openHelper.readableDatabase
            .query("SELECT access_state FROM media_sources WHERE media_store_id = ?", arrayOf<Any>(mediaStoreId))
            .use { it.moveToFirst(); it.getString(0) }
}

private const val TEST_DATABASE = "catalog-sync-test.db"
