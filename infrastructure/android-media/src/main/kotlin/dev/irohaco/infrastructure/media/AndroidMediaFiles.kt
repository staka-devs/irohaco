package dev.irohaco.infrastructure.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.core.contracts.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Move and rename update the MediaStore row, so its id and the asset stay the same. Copy inserts a
 * new row as this app and fills it while pending, so no other app sees a half-written file.
 */
class AndroidMediaFiles(private val context: Context) : MediaFiles {
    private val resolver get() = context.contentResolver

    override suspend fun folders(): List<String> = withContext(Dispatchers.IO) {
        val folders = sortedSetOf<String>()
        // shortcut: folders of all volumes are merged; split them by volume if SD cards need their own.
        for (collection in listOf(IMAGES, VIDEOS)) {
            resolver.query(collection, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { folders += it }
            }
        }
        folders.filter { folder -> SHARED_ROOTS.any { folder.startsWith(it) } }
    }

    override suspend fun name(contentUri: String): String? = withContext(Dispatchers.IO) { row(Uri.parse(contentUri))?.name }

    override suspend fun move(contentUri: String, folder: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(contentUri)
        val row = row(uri) ?: return@withContext FileOperationResult.NOT_FOUND
        when {
            row.folder == folder -> FileOperationResult.DONE
            exists(uri, folder, row.name) -> FileOperationResult.NAME_TAKEN
            else -> update(uri, MediaStore.MediaColumns.RELATIVE_PATH, folder)
        }
    }

    override suspend fun rename(contentUri: String, fileName: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(contentUri)
        val row = row(uri) ?: return@withContext FileOperationResult.NOT_FOUND
        when {
            row.name == fileName -> FileOperationResult.DONE
            exists(uri, row.folder, fileName) -> FileOperationResult.NAME_TAKEN
            else -> update(uri, MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        }
    }

    override suspend fun copy(contentUri: String, folder: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(contentUri)
        val row = row(uri) ?: return@withContext FileOperationResult.NOT_FOUND
        val collection = if (row.mime.startsWith("video/")) VIDEOS_PRIMARY else IMAGES_PRIMARY
        // MediaStore appends " (1)" when the folder already has the name, so a copy never overwrites.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, row.name)
            put(MediaStore.MediaColumns.MIME_TYPE, row.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val copy = try {
            resolver.insert(collection, values)
        } catch (e: IllegalArgumentException) {
            null // a folder MediaStore does not accept for this type
        } ?: return@withContext FileOperationResult.FAILED
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            input.use { i -> resolver.openOutputStream(copy)!!.use { o -> i.copyTo(o) } }
            resolver.update(copy, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            FileOperationResult.DONE
        } catch (e: IOException) {
            resolver.delete(copy, null, null)
            FileOperationResult.FAILED
        } catch (e: SecurityException) {
            resolver.delete(copy, null, null)
            FileOperationResult.FAILED
        }
    }

    private class Row(val name: String, val folder: String, val mime: String)

    private fun row(uri: Uri): Row? = try {
        resolver.query(uri, ROW, null, null, null)?.use { c ->
            if (c.moveToFirst()) Row(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty()) else null
        }
    } catch (e: SecurityException) {
        null
    }

    /** Any file of that name in the folder blocks the change, whatever its type. */
    private fun exists(uri: Uri, folder: String, name: String): Boolean =
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.getVolumeName(uri)),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(folder, name),
            null,
        )?.use { it.count > 0 } ?: false

    private fun update(uri: Uri, column: String, value: String): FileOperationResult = try {
        val changed = resolver.update(uri, ContentValues().apply { put(column, value) }, null, null)
        if (changed > 0) FileOperationResult.DONE else FileOperationResult.NOT_FOUND
    } catch (e: SecurityException) {
        FileOperationResult.FAILED // no write grant
    } catch (e: IllegalArgumentException) {
        FileOperationResult.FAILED // a folder or name MediaStore rejects
    } catch (e: IllegalStateException) {
        FileOperationResult.FAILED
    }

    private companion object {
        val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val IMAGES_PRIMARY: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val VIDEOS_PRIMARY: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val SHARED_ROOTS = listOf("DCIM/", "Pictures/")
        val ROW = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE)
    }
}
