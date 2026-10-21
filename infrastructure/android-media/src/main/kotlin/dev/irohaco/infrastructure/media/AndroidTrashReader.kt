package dev.irohaco.infrastructure.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import dev.irohaco.core.contracts.TrashReader
import dev.irohaco.core.contracts.TrashedItem
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MediaStore's own trash, across every volume (PRD GAL-015): it shows what any app put there. */
class AndroidTrashReader(private val context: Context) : TrashReader {
    override suspend fun list(): List<TrashedItem> = withContext(Dispatchers.IO) {
        MediaStore.getExternalVolumeNames(context).flatMap { volume ->
            collections(volume).flatMap { (kind, uri) -> query(uri, kind) }
        }
    }

    private fun collections(volume: String) = listOf(
        MediaKind.IMAGE to MediaStore.Images.Media.getContentUri(volume),
        MediaKind.VIDEO to MediaStore.Video.Media.getContentUri(volume),
    )

    private fun query(collection: Uri, kind: MediaKind): List<TrashedItem> {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        return context.contentResolver.query(collection, PROJECTION, args, null)?.use { it.items(collection, kind) }.orEmpty()
    }

    private fun Cursor.items(collection: Uri, kind: MediaKind): List<TrashedItem> {
        val id = getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val name = getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val expires = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
        return buildList {
            while (moveToNext()) {
                val mediaStoreId = getLong(id)
                add(
                    TrashedItem(
                        mediaStoreId = mediaStoreId,
                        contentUri = ContentUris.withAppendedId(collection, mediaStoreId).toString(),
                        kind = kind,
                        displayName = getString(name).orEmpty(),
                        expiresAtMs = if (isNull(expires)) null else getLong(expires) * 1000, // DATE_EXPIRES is in seconds
                    ),
                )
            }
        }
    }

    private companion object {
        val PROJECTION = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_EXPIRES)
    }
}
