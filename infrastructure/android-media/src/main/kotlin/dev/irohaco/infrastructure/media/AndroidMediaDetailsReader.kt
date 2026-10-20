package dev.irohaco.infrastructure.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import dev.irohaco.core.contracts.MediaDetailsReader
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaDetails
import dev.irohaco.core.domain.MediaDetailsResult
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * MediaStore columns plus EXIF/XMP for photos and the container metadata for videos (PRD MET-001).
 * Asks the OS for the location permission each time (ADR-0009) and reads the unredacted original only
 * when it is granted.
 */
class AndroidMediaDetailsReader(private val context: Context) : MediaDetailsReader {
    override suspend fun read(contentUri: String, kind: MediaKind): MediaDetailsResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(contentUri)
        val locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        val source = if (locationAllowed) MediaStore.setRequireOriginal(uri) else uri
        try {
            val row = catalogRow(uri) ?: return@withContext MediaDetailsResult.FileUnavailable
            MediaDetailsResult.Read(
                when (kind) {
                    MediaKind.IMAGE -> photoDetails(row, source, locationAllowed)
                    MediaKind.VIDEO -> videoDetails(row, source, locationAllowed)
                },
            )
        } catch (e: SecurityException) {
            MediaDetailsResult.FileUnavailable
        } catch (e: FileNotFoundException) {
            MediaDetailsResult.FileUnavailable
        }
    }

    private class Row(
        val name: String,
        val folder: Detail<String>,
        val size: Detail<Long>,
        val mime: Detail<String>,
        val width: Detail<Int>,
        val height: Detail<Int>,
        val durationMs: Detail<Long>,
    )

    private fun catalogRow(uri: Uri): Row? =
        context.contentResolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            Row(
                name = c.string(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty(),
                folder = c.string(MediaStore.MediaColumns.RELATIVE_PATH).detail(),
                size = c.long(MediaStore.MediaColumns.SIZE).positive(),
                mime = c.string(MediaStore.MediaColumns.MIME_TYPE).detail(),
                width = c.long(MediaStore.MediaColumns.WIDTH).positive().asInt(),
                height = c.long(MediaStore.MediaColumns.HEIGHT).positive().asInt(),
                durationMs = c.long(MediaStore.MediaColumns.DURATION).positive(),
            )
        }

    private fun photoDetails(row: Row, source: Uri, locationAllowed: Boolean): MediaDetails {
        val photo = try {
            // IPTC sits in the JPEG header; reading it first walks only the header segments.
            val iptc = context.contentResolver.openInputStream(source)?.use { readIptc(it) } ?: IptcFields.missing
            context.contentResolver.openInputStream(source)?.use { readPhotoFields(it, locationAllowed, iptc) } ?: PhotoFields.unreadable
        } catch (e: IOException) {
            if (e is FileNotFoundException) throw e
            PhotoFields.unreadable
        }
        return MediaDetails(
            fileName = row.name, folder = row.folder, sizeBytes = row.size, mimeType = row.mime,
            widthPx = row.width, heightPx = row.height, takenAt = photo.takenAt, durationMs = Detail.Missing,
            camera = photo.camera, lens = photo.lens, fNumber = photo.fNumber, exposureSeconds = photo.exposureSeconds,
            iso = photo.iso, focalLengthMm = photo.focalLengthMm, location = photo.location,
            description = photo.description, keywords = photo.keywords,
            headline = photo.headline, creator = photo.creator, copyright = photo.copyright,
        )
    }

    private fun videoDetails(row: Row, source: Uri, locationAllowed: Boolean): MediaDetails {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(context, source)
            } catch (e: RuntimeException) {
                return videoFrom(row, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            return videoFrom(
                row,
                takenAt = videoDate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)),
                duration = duration?.let { Detail.Value(it) } ?: row.durationMs,
                location = parseIso6709(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION), locationAllowed),
            )
        } finally {
            retriever.release()
        }
    }

    private fun videoFrom(row: Row, takenAt: Detail<LocalDateTime>, duration: Detail<Long>, location: Detail<GeoPoint>) =
        MediaDetails(
            fileName = row.name, folder = row.folder, sizeBytes = row.size, mimeType = row.mime,
            widthPx = row.width, heightPx = row.height, takenAt = takenAt, durationMs = duration,
            camera = Detail.Missing, lens = Detail.Missing, fNumber = Detail.Missing, exposureSeconds = Detail.Missing,
            iso = Detail.Missing, focalLengthMm = Detail.Missing, location = location,
            description = Detail.Missing, keywords = Detail.Missing,
        )

    /** MediaMetadataRetriever's date is UTC, e.g. "20261020T103000.000Z"; shown in local time. */
    private fun videoDate(raw: String?): Detail<LocalDateTime> {
        if (raw.isNullOrBlank() || raw.startsWith("1904")) return Detail.Missing // 1904 is the MP4 "no date" epoch
        return try {
            val utc = LocalDateTime.parse(raw.trim().removeSuffix("Z"), VIDEO_DATE)
            Detail.Value(LocalDateTime.ofInstant(utc.toInstant(ZoneOffset.UTC), ZoneId.systemDefault()))
        } catch (e: DateTimeParseException) {
            Detail.Unreadable
        }
    }

    private companion object {
        val VIDEO_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS")
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )
    }
}

private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

private fun Cursor.long(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }

private fun String?.detail(): Detail<String> = this?.takeIf { it.isNotBlank() }?.let { Detail.Value(it) } ?: Detail.Missing

private fun Long?.positive(): Detail<Long> = this?.takeIf { it > 0 }?.let { Detail.Value(it) } ?: Detail.Missing

private fun Detail<Long>.asInt(): Detail<Int> = if (this is Detail.Value) Detail.Value(value.toInt()) else Detail.Missing
