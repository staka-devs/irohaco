package dev.irohaco.infrastructure.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.core.contracts.MetadataField
import dev.irohaco.core.contracts.MetadataFiles
import dev.irohaco.core.contracts.geoPointOfMetadataText
import dev.irohaco.core.domain.Detail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Writes edits into a photo's EXIF (ADR-0030). The original is copied aside, the values go into a working copy
 * that is read back first, and only then does the working copy replace the original, which is read back again.
 * Both copies stay in the app's own files until [discard], so a stopped process can still restore the original.
 */
class AndroidMetadataFiles(private val context: Context, private val zone: () -> ZoneId = ZoneId::systemDefault) : MetadataFiles {
    private val resolver get() = context.contentResolver
    private val dir: File get() = File(context.filesDir, "metadata-writes").apply { mkdirs() }

    private fun backup(operationId: String) = File(dir, "$operationId.bak")

    private fun work(operationId: String) = File(dir, "$operationId.work")

    override suspend fun stage(operationId: String, contentUri: String, values: Map<MetadataField, String?>) = io {
        // Without the permission the OS hands over the file with its GPS removed, which would then replace the original.
        if (!locationAllowed()) return@io false
        val backup = backup(operationId)
        val work = work(operationId)
        openOriginal(contentUri)?.use { input -> backup.outputStream().use { input.copyTo(it) } } ?: return@io false
        backup.copyTo(work, overwrite = true)
        write(work, values, removeLocation = false)
        holds(work.readBytes(), values) && sameImage(work.readBytes(), backup.readBytes())
    }

    override suspend fun replace(operationId: String, contentUri: String) = io { put(work(operationId), contentUri) }

    override suspend fun verify(operationId: String, contentUri: String, values: Map<MetadataField, String?>) = io {
        val written = openOriginal(contentUri)?.use { it.readBytes() } ?: return@io false
        holds(written, values) && sameImage(written, backup(operationId).readBytes())
    }

    override suspend fun restore(operationId: String, contentUri: String) = io {
        backup(operationId).exists() && put(backup(operationId), contentUri)
    }

    override suspend fun discard(operationId: String) {
        withContext(Dispatchers.IO) {
            backup(operationId).delete()
            work(operationId).delete()
        }
    }

    override suspend fun saveCopy(contentUri: String, values: Map<MetadataField, String?>, removeLocation: Boolean) = io {
        val file = prepareCopy(contentUri, values, removeLocation) ?: return@io false
        try {
            AndroidMediaFiles(context).copyBeside(contentUri) { file.inputStream() } == FileOperationResult.DONE
        } finally {
            file.delete()
        }
    }

    /** The copy to save, written and read back; null when it does not hold the values. */
    internal fun prepareCopy(contentUri: String, values: Map<MetadataField, String?>, removeLocation: Boolean): File? {
        val file = File(context.cacheDir, "copy-${System.nanoTime()}")
        // shortcut: without the permission the copy loses the file's own GPS; ask for it first if copies must keep it.
        openOriginal(contentUri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        write(file, values, removeLocation)
        val expected = if (removeLocation) values + (MetadataField.LOCATION to null) else values
        return file.takeIf { holds(it.readBytes(), expected) } ?: run {
            file.delete()
            null
        }
    }

    private fun locationAllowed() =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun openOriginal(contentUri: String): InputStream? {
        val uri = Uri.parse(contentUri)
        val source = if (uri.scheme == "content" && locationAllowed()) MediaStore.setRequireOriginal(uri) else uri
        return resolver.openInputStream(source)
    }

    private fun put(file: File, contentUri: String): Boolean =
        resolver.openOutputStream(Uri.parse(contentUri), "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } != null

    private fun write(file: File, values: Map<MetadataField, String?>, removeLocation: Boolean) {
        val exif = ExifInterface(file.path)
        values.forEach { (field, text) ->
            when (field) {
                MetadataField.TAKEN -> {
                    val taken = text?.let(LocalDateTime::parse)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, taken?.let(EXIF_DATE::format))
                    exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, taken?.atZone(zone())?.let(OFFSET::format))
                }
                MetadataField.LOCATION -> {
                    // The old point's altitude, time and direction do not belong to a new one.
                    GPS_TAGS.forEach { exif.setAttribute(it, null) }
                    text?.let(::geoPointOfMetadataText)?.let { exif.setLatLong(it.latitude, it.longitude) }
                }
                MetadataField.DESCRIPTION -> exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, text)
            }
        }
        if (removeLocation) GPS_TAGS.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
    }

    /** Whether the app reads [values] from [bytes] as it reads any photo, so XMP or IPTC that would win counts against it. */
    private fun holds(bytes: ByteArray, values: Map<MetadataField, String?>): Boolean {
        val fields = readPhotoFields(bytes.inputStream(), locationAllowed = true, iptc = readIptc(bytes.inputStream()))
        return values.all { (field, text) ->
            when (field) {
                MetadataField.TAKEN -> fields.takenAt == (text?.let { Detail.Value(LocalDateTime.parse(it).withNano(0)) } ?: Detail.Missing)
                MetadataField.LOCATION -> {
                    val read = (fields.location as? Detail.Value)?.value
                    val want = text?.let(::geoPointOfMetadataText)
                    // EXIF keeps thousandths of a second of arc, about 3 cm.
                    if (want == null || read == null) want == read else abs(read.latitude - want.latitude) < 1e-6 && abs(read.longitude - want.longitude) < 1e-6
                }
                MetadataField.DESCRIPTION -> fields.description == (text?.trim()?.takeIf { it.isNotEmpty() }?.let { Detail.Value(it) } ?: Detail.Missing)
            }
        }
    }

    private suspend fun io(block: suspend () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
private val OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("xxx")

/** Whether the image data, from the first start-of-scan marker on, is byte for byte the same (ADR-0030). */
internal fun sameImage(a: ByteArray, b: ByteArray): Boolean {
    val sa = startOfScan(a)
    val sb = startOfScan(b)
    if (sa < 0 || sb < 0 || a.size - sa != b.size - sb) return false
    return (0 until a.size - sa).all { a[sa + it] == b[sb + it] }
}

/** The offset of a JPEG's first SOS marker, walking the header segments; -1 when there is none. */
internal fun startOfScan(jpeg: ByteArray): Int {
    if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return -1
    var i = 2
    while (i + 3 < jpeg.size) {
        if (jpeg[i] != 0xFF.toByte()) return -1
        val marker = jpeg[i + 1].toInt() and 0xFF
        when (marker) {
            0xFF -> i++ // fill byte
            0xDA -> return i
            else -> i += 2 + ((jpeg[i + 2].toInt() and 0xFF) shl 8 or (jpeg[i + 3].toInt() and 0xFF))
        }
    }
    return -1
}

/** Every EXIF tag that tells where a photo was taken (MET-003). */
internal val GPS_TAGS = arrayOf(
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
