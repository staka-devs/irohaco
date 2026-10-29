package dev.irohaco.infrastructure.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.LibraryAccess
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Search results are checked against the OS at search time, not against the last sync (ADR-0009, IST-008). */
@RunWith(RobolectricTestRunner::class)
class AndroidMediaAccessCheckTest {
    /** MediaStore under partial access: it lists only the photos still selected. */
    class SelectedMedia : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?) =
            MatrixCursor(arrayOf("_id")).apply { args.orEmpty().filter { it == "1" }.forEach { addRow(arrayOf(it.toLong())) } }
        override fun getType(uri: Uri) = null
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photos = listOf(1, 2).map { TimelineItem("p$it", MediaKind.IMAGE, "content://media/external/images/media/$it", 0) }
    private val video = TimelineItem("v1", MediaKind.VIDEO, "content://media/external/video/media/3", 0)

    @Test
    fun `under partial access only the files still selected are let through`() = runTest {
        Robolectric.setupContentProvider(SelectedMedia::class.java, "media")
        val check = AndroidMediaAccessCheck(context) { MediaLibraryAccess(images = LibraryAccess.SELECTED, videos = LibraryAccess.FULL) }
        assertEquals(listOf("p1", "v1"), check.readableNow(photos + video).map { it.assetId })
    }

    @Test
    fun `without access nothing is let through`() = runTest {
        val check = AndroidMediaAccessCheck(context) { MediaLibraryAccess(images = LibraryAccess.NONE, videos = LibraryAccess.NONE) }
        assertEquals(emptyList(), check.readableNow(photos + video))
    }
}
