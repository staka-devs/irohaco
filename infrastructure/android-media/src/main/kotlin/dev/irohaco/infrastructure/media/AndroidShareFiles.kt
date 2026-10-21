package dev.irohaco.infrastructure.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import dev.irohaco.core.contracts.ShareFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A copy of a photo in the app's own cache, with or without its GPS, ready to share (PRD GAL-005,
 * MET-003). A copy is made either way: the receiving app's own read of the original would normally
 * have its location redacted by the OS, regardless of what the user chose here. The original file is
 * never opened for writing. [cleanUp] is time-based, not tied to the share sheet finishing with the
 * file: there is no OS callback for that.
 */
class AndroidShareFiles(private val context: Context) : ShareFiles {
    private val dir: File get() = File(context.cacheDir, "share").apply { mkdirs() }

    override suspend fun prepareCopy(contentUri: String, keepLocation: Boolean): String? = withContext(Dispatchers.IO) {
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        try {
            val locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
            val uri = Uri.parse(contentUri)
            // Only a read of the unredacted original can carry GPS past the OS's own redaction; without
            // the permission, "keep" falls back to whatever the OS hands back.
            val source = if (keepLocation && locationAllowed) MediaStore.setRequireOriginal(uri) else uri
            val copied = context.contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
                true
            } ?: false
            if (!copied) return@withContext null
            if (!keepLocation) stripGps(file)
            FileProvider.getUriForFile(context, AUTHORITY, file).toString()
        } catch (e: IOException) {
            file.delete()
            null
        } catch (e: SecurityException) {
            file.delete()
            null
        }
    }

    private fun stripGps(file: File) {
        val exif = ExifInterface(file.path)
        GPS_TAGS.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
    }

    override suspend fun cleanUp() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        Unit
    }

    private companion object {
        // Matches the <provider> authority declared in this module's AndroidManifest.xml exactly.
        const val AUTHORITY = "dev.irohaco.infrastructure.media.shareprovider"
        const val RETENTION_MS = 60 * 60 * 1_000L

        val GPS_TAGS = arrayOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        )
    }
}
