package dev.irohaco.infrastructure.media

import android.util.Xml
import androidx.exifinterface.media.ExifInterface
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The photo-only fields of the details sheet. */
internal data class PhotoFields(
    val takenAt: Detail<LocalDateTime>,
    val camera: Detail<String>,
    val lens: Detail<String>,
    val fNumber: Detail<Double>,
    val exposureSeconds: Detail<Double>,
    val iso: Detail<Int>,
    val focalLengthMm: Detail<Double>,
    val location: Detail<GeoPoint>,
    val description: Detail<String>,
    val keywords: Detail<List<String>>,
    val headline: Detail<String> = Detail.Missing,
    val creator: Detail<String> = Detail.Missing,
    val copyright: Detail<String> = Detail.Missing,
) {
    companion object {
        val unreadable = PhotoFields(
            Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable,
            Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable,
            Detail.Unreadable, Detail.Unreadable, Detail.Unreadable,
        )
    }
}

private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/**
 * Reads EXIF and XMP from a photo. Without [locationAllowed] the OS has already removed GPS from the
 * stream, so the location is reported as NoPermission rather than Missing.
 */
internal fun readPhotoFields(input: InputStream, locationAllowed: Boolean, iptc: IptcFields = IptcFields.missing): PhotoFields {
    val exif = try {
        ExifInterface(input)
    } catch (e: IOException) {
        return PhotoFields.unreadable
    }
    val xmp = exif.getAttributeBytes(ExifInterface.TAG_XMP)?.let { parseXmp(it.decodeToString()) }
    return PhotoFields(
        takenAt = exif.date(ExifInterface.TAG_DATETIME_ORIGINAL),
        camera = joined(exif.text(ExifInterface.TAG_MAKE), exif.text(ExifInterface.TAG_MODEL)),
        lens = joined(exif.text(ExifInterface.TAG_LENS_MAKE), exif.text(ExifInterface.TAG_LENS_MODEL)),
        fNumber = exif.number(ExifInterface.TAG_F_NUMBER),
        exposureSeconds = exif.number(ExifInterface.TAG_EXPOSURE_TIME),
        iso = exif.number(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY).map { it.toInt() },
        focalLengthMm = exif.number(ExifInterface.TAG_FOCAL_LENGTH),
        location = when {
            !locationAllowed -> Detail.NoPermission
            else -> exif.latLong?.let { Detail.Value(GeoPoint(it[0], it[1])) } ?: Detail.Missing
        },
        // XMP first, then IPTC, then the EXIF description (#83).
        description = preferXmp(xmp?.description, iptc.caption).takeIf { it is Detail.Value } ?: exif.text(ExifInterface.TAG_IMAGE_DESCRIPTION),
        keywords = preferXmp(xmp?.keywords, iptc.keywords),
        headline = preferXmp(xmp?.headline, iptc.headline),
        creator = preferXmp(xmp?.creator, iptc.creator),
        copyright = preferXmp(xmp?.copyright, iptc.copyright),
    )
}

private fun ExifInterface.text(tag: String): Detail<String> =
    getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }?.let { Detail.Value(it) } ?: Detail.Missing

private fun ExifInterface.number(tag: String): Detail<Double> {
    if (getAttribute(tag) == null) return Detail.Missing
    val value = getAttributeDouble(tag, Double.NaN)
    return if (value.isNaN()) Detail.Unreadable else Detail.Value(value)
}

private fun ExifInterface.date(tag: String): Detail<LocalDateTime> {
    val raw = getAttribute(tag)?.trim() ?: return Detail.Missing
    return try {
        Detail.Value(LocalDateTime.parse(raw, EXIF_DATE))
    } catch (e: DateTimeParseException) {
        Detail.Unreadable
    }
}

/** "Make Model", without repeating the make when the model already starts with it. */
private fun joined(make: Detail<String>, model: Detail<String>): Detail<String> = when {
    make is Detail.Value && model is Detail.Value ->
        Detail.Value(if (model.value.startsWith(make.value, ignoreCase = true)) model.value else "${make.value} ${model.value}")
    model is Detail.Value -> model
    else -> make
}

private fun <T, R> Detail<T>.map(transform: (T) -> R): Detail<R> = when (this) {
    is Detail.Value -> Detail.Value(transform(value))
    Detail.Missing -> Detail.Missing
    Detail.NoPermission -> Detail.NoPermission
    Detail.Unreadable -> Detail.Unreadable
}

internal data class XmpFields(
    val description: Detail<String>,
    val keywords: Detail<List<String>>,
    val headline: Detail<String> = Detail.Missing,
    val creator: Detail<String> = Detail.Missing,
    val copyright: Detail<String> = Detail.Missing,
)

private const val DUBLIN_CORE = "http://purl.org/dc/elements/1.1/"
private const val PHOTOSHOP_NS = "http://ns.adobe.com/photoshop/1.0/"
private val DC_LISTS = setOf("description", "subject", "creator", "rights")

/**
 * dc:description, dc:subject, dc:creator, dc:rights and photoshop:Headline (an element or an
 * attribute) from an XMP packet; a malformed packet makes them all Unreadable.
 */
internal fun parseXmp(xmp: String): XmpFields {
    var description: String? = null
    var copyright: String? = null
    var headline: String? = null
    val keywords = mutableListOf<String>()
    val creators = mutableListOf<String>()
    try {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xmp))
        }
        var field: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    headline = headline ?: parser.getAttributeValue(PHOTOSHOP_NS, "Headline")?.trim()?.takeIf { it.isNotEmpty() }
                    when {
                        parser.namespace == DUBLIN_CORE && parser.name in DC_LISTS -> field = parser.name
                        parser.namespace == PHOTOSHOP_NS && parser.name == "Headline" ->
                            headline = headline ?: parser.nextText().trim().takeIf { it.isNotEmpty() }
                        parser.name == "li" && field != null -> {
                            val text = parser.nextText().trim()
                            if (text.isNotEmpty()) {
                                when (field) {
                                    "description" -> description = description ?: text
                                    "rights" -> copyright = copyright ?: text
                                    "creator" -> creators += text
                                    else -> keywords += text
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.namespace == DUBLIN_CORE && parser.name == field) field = null
            }
        }
    } catch (e: XmlPullParserException) {
        return XMP_UNREADABLE
    } catch (e: IOException) {
        return XMP_UNREADABLE
    }
    return XmpFields(
        description = description?.let { Detail.Value(it) } ?: Detail.Missing,
        keywords = if (keywords.isEmpty()) Detail.Missing else Detail.Value(keywords),
        headline = headline?.let { Detail.Value(it) } ?: Detail.Missing,
        creator = if (creators.isEmpty()) Detail.Missing else Detail.Value(creators.joinToString(", ")),
        copyright = copyright?.let { Detail.Value(it) } ?: Detail.Missing,
    )
}

private val XMP_UNREADABLE = XmpFields(Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable)

private val ISO_6709 = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")

/** A video's location as MediaMetadataRetriever reports it, e.g. "+35.6895+139.6917/". */
internal fun parseIso6709(value: String?, locationAllowed: Boolean): Detail<GeoPoint> {
    if (!locationAllowed) return Detail.NoPermission
    if (value.isNullOrBlank()) return Detail.Missing
    val match = ISO_6709.find(value.trim()) ?: return Detail.Unreadable
    return Detail.Value(GeoPoint(match.groupValues[1].toDouble(), match.groupValues[2].toDouble()))
}
