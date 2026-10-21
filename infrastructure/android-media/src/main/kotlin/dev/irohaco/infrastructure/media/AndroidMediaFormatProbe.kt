package dev.irohaco.infrastructure.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dev.irohaco.core.contracts.MediaFormatProbe
import dev.irohaco.core.domain.MediaFormat
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.detectFormat
import dev.irohaco.core.domain.isAnimatedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** MIME type and name from MediaStore, XMP from the file's header (GAL-009). */
class AndroidMediaFormatProbe(private val context: Context) : MediaFormatProbe {
    override suspend fun probe(contentUri: String, kind: MediaKind): MediaFormat = withContext(Dispatchers.IO) {
        if (kind != MediaKind.IMAGE) return@withContext MediaFormat()
        val uri = Uri.parse(contentUri)
        try {
            val (mime, name) = context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null } ?: (null to null)
            val xmp = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeBytes(ExifInterface.TAG_XMP)?.decodeToString() }
            val header = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(HEADER_BYTES) } ?: ByteArray(0)
            detectFormat(mime, name, xmp).copy(animated = isAnimatedImage(mime, header))
        } catch (e: IOException) {
            MediaFormat()
        } catch (e: SecurityException) {
            MediaFormat()
        }
    }

    private companion object {
        const val HEADER_BYTES = 32
    }
}
