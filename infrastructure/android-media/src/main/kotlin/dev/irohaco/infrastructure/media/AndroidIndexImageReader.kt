package dev.irohaco.infrastructure.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import dev.irohaco.core.contracts.IndexImageReader
import dev.irohaco.core.contracts.IndexItemSkipped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * Hands the model a 768 px copy from MediaStore, re-encoded as JPEG (ImageSpaces.LOCAL's preprocessing).
 * The model scales images down anyway; a small copy keeps decoding off the full-size file.
 */
class AndroidIndexImageReader(private val context: Context) : IndexImageReader {
    override suspend fun read(contentUri: String): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = try {
            context.contentResolver.loadThumbnail(Uri.parse(contentUri), Size(SIDE_PX, SIDE_PX), null)
        } catch (e: FileNotFoundException) {
            throw IndexItemSkipped("$contentUri is gone")
        }
        try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val SIDE_PX = 768
        const val QUALITY = 90
    }
}
