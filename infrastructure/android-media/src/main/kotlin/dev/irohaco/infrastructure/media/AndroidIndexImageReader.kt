package dev.irohaco.infrastructure.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import dev.irohaco.core.contracts.IndexImageReader
import dev.irohaco.core.contracts.IndexItemSkipped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Hands the model a 768 px copy re-encoded as JPEG (ImageSpaces.LOCAL's preprocessing): MediaStore's
 * small copy for images, and for videos the key frame nearest the asked time.
 */
class AndroidIndexImageReader(private val context: Context) : IndexImageReader {
    override suspend fun read(contentUri: String): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = try {
            context.contentResolver.loadThumbnail(Uri.parse(contentUri), Size(SIDE_PX, SIDE_PX), null)
        } catch (e: FileNotFoundException) {
            throw IndexItemSkipped("$contentUri is gone")
        }
        jpeg(bitmap)
    }

    // shortcut: the platform retriever and the nearest key frame stand in for Media3's FrameExtractor;
    // switch when the spike measures that exact frames find short scenes better.
    override suspend fun readFrame(contentUri: String, timestampMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(contentUri))
            val frame = retriever.getScaledFrameAtTime(timestampMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIDE_PX, SIDE_PX)
                ?: throw IOException("No frame at $timestampMs ms in $contentUri")
            jpeg(frame)
        } finally {
            retriever.release()
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = try {
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
    } finally {
        bitmap.recycle()
    }

    private companion object {
        const val SIDE_PX = 768
        const val QUALITY = 90
    }
}
