package dev.irohaco.infrastructure.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import dev.irohaco.core.contracts.MediaCatalogSource
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore images and videos per external volume (ARCHITECTURE §6.1). Queries only return what the
 * app may read now, and by default leave out pending and trashed items.
 */
class MediaStoreCatalogSource(private val context: Context) : MediaCatalogSource {
    private val resolver get() = context.contentResolver

    override suspend fun volumes(): Set<String> = withContext(Dispatchers.IO) { MediaStore.getExternalVolumeNames(context) }

    override suspend fun version(volume: String): String = withContext(Dispatchers.IO) { MediaStore.getVersion(context, volume) }

    override suspend fun generation(volume: String): Long = withContext(Dispatchers.IO) { MediaStore.getGeneration(context, volume) }

    override suspend fun changedSince(volume: String, sinceGeneration: Long): List<MediaStoreEntry> =
        query(volume, "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?", arrayOf(sinceGeneration.toString()))

    override suspend fun visibleIds(volume: String): Set<Long> = withContext(Dispatchers.IO) {
        collections(volume).flatMap { (_, uri) ->
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null).ids()
        }.toSet()
    }

    override suspend fun entries(volume: String, ids: Set<Long>): List<MediaStoreEntry> =
        ids.chunked(ID_CHUNK).flatMap { chunk ->
            query(volume, "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString { "?" }})", chunk.map { it.toString() }.toTypedArray())
        }

    private suspend fun query(volume: String, selection: String, args: Array<String>): List<MediaStoreEntry> =
        withContext(Dispatchers.IO) {
            collections(volume).flatMap { (kind, uri) ->
                resolver.query(uri, PROJECTION, selection, args, null)?.use { c -> c.entries(kind, uri) }.orEmpty()
            }
        }

    private fun collections(volume: String) = listOf(
        MediaKind.IMAGE to MediaStore.Images.Media.getContentUri(volume),
        MediaKind.VIDEO to MediaStore.Video.Media.getContentUri(volume),
    )

    private fun Cursor.entries(kind: MediaKind, collection: Uri): List<MediaStoreEntry> {
        val id = getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val name = getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val path = getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val mime = getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val size = getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val modified = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val taken = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val result = ArrayList<MediaStoreEntry>(count)
        while (moveToNext()) {
            val mediaStoreId = getLong(id)
            result += MediaStoreEntry(
                mediaStoreId = mediaStoreId,
                kind = kind,
                contentUri = ContentUris.withAppendedId(collection, mediaStoreId).toString(),
                displayName = getString(name).orEmpty(),
                relativePath = getString(path),
                mimeType = getString(mime) ?: "application/octet-stream",
                sizeBytes = getLong(size),
                modifiedAtMs = if (isNull(modified)) null else getLong(modified) * 1000, // DATE_MODIFIED is in seconds
                takenAtMs = if (isNull(taken)) null else getLong(taken),
            )
        }
        return result
    }

    private fun Cursor?.ids(): List<Long> = this?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        buildList { while (c.moveToNext()) add(c.getLong(id)) }
    }.orEmpty()

    private companion object {
        const val ID_CHUNK = 500
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
    }
}
