package dev.irohaco.infrastructure.media

import androidx.exifinterface.media.ExifInterface
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MetadataParsingTest {
    @get:Rule val folder = TemporaryFolder()

    private fun jpeg(tags: Map<String, String> = emptyMap(), gps: Pair<Double, Double>? = null): File {
        val file = folder.newFile("photo.jpg")
        javaClass.getResourceAsStream("/plain.jpg")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        if (tags.isNotEmpty() || gps != null) {
            ExifInterface(file).apply {
                tags.forEach { (tag, value) -> setAttribute(tag, value) }
                gps?.let { setLatLong(it.first, it.second) }
                saveAttributes()
            }
        }
        return file
    }

    private val fullTags = mapOf(
        ExifInterface.TAG_DATETIME_ORIGINAL to "2026:10:20 09:15:30",
        ExifInterface.TAG_MAKE to "Maker",
        ExifInterface.TAG_MODEL to "Maker Model 7",
        ExifInterface.TAG_F_NUMBER to "1.8",
        ExifInterface.TAG_EXPOSURE_TIME to "0.004",
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "200",
        ExifInterface.TAG_FOCAL_LENGTH to "26/1",
        ExifInterface.TAG_IMAGE_DESCRIPTION to "Harbour at dawn",
    )

    @Test
    fun `reads EXIF values and the location when it is allowed`() {
        val fields = jpeg(fullTags, gps = 35.5 to 139.75).inputStream().use { readPhotoFields(it, locationAllowed = true) }
        assertEquals(Detail.Value(LocalDateTime.of(2026, 10, 20, 9, 15, 30)), fields.takenAt)
        assertEquals(Detail.Value("Maker Model 7"), fields.camera) // the make is not repeated
        assertEquals(Detail.Value(1.8), fields.fNumber)
        assertEquals(Detail.Value(0.004), fields.exposureSeconds)
        assertEquals(Detail.Value(200), fields.iso)
        assertEquals(Detail.Value(26.0), fields.focalLengthMm)
        assertEquals(Detail.Value("Harbour at dawn"), fields.description)
        val location = fields.location as Detail.Value<GeoPoint>
        assertEquals(35.5, location.value.latitude, 1e-4)
        assertEquals(139.75, location.value.longitude, 1e-4)
    }

    @Test
    fun `without the location permission the location is withheld, not missing`() {
        val fields = jpeg(fullTags, gps = 35.5 to 139.75).inputStream().use { readPhotoFields(it, locationAllowed = false) }
        assertEquals(Detail.NoPermission, fields.location)
    }

    @Test
    fun `absent tags are missing`() {
        val fields = jpeg().inputStream().use { readPhotoFields(it, locationAllowed = true) }
        listOf(fields.takenAt, fields.camera, fields.lens, fields.fNumber, fields.iso, fields.location, fields.description, fields.keywords)
            .forEach { assertEquals(Detail.Missing, it) }
    }

    @Test
    fun `a stored value that cannot be parsed is unreadable`() {
        // ExifInterface only stores dates in its format, so the impossible date is format-valid.
        val fields = jpeg(mapOf(ExifInterface.TAG_DATETIME_ORIGINAL to "2026:13:45 99:99:99"))
            .inputStream().use { readPhotoFields(it, locationAllowed = true) }
        assertEquals(Detail.Unreadable, fields.takenAt)
    }

    @Test
    fun `XMP description and keywords`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:description><rdf:Alt><rdf:li xml:lang="x-default">Festival night</rdf:li></rdf:Alt></dc:description>
              <dc:subject><rdf:Bag><rdf:li>fireworks</rdf:li><rdf:li>summer</rdf:li></rdf:Bag></dc:subject>
            </rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent()
        assertEquals(XmpFields(Detail.Value("Festival night"), Detail.Value(listOf("fireworks", "summer"))), parseXmp(xmp))
        assertEquals(XmpFields(Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable), parseXmp("<x:xmpmeta><dc:subject>"))
        assertEquals(XmpFields(Detail.Missing, Detail.Missing), parseXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"))
    }

    @Test
    fun `XMP headline as an attribute, creators and rights`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
                photoshop:Headline="Harbour at dawn">
              <dc:creator><rdf:Seq><rdf:li>A. Photographer</rdf:li><rdf:li>B. Assistant</rdf:li></rdf:Seq></dc:creator>
              <dc:rights><rdf:Alt><rdf:li xml:lang="x-default">© 2026 A. Photographer</rdf:li></rdf:Alt></dc:rights>
            </rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent()
        val fields = parseXmp(xmp)
        assertEquals(Detail.Value("Harbour at dawn"), fields.headline)
        assertEquals(Detail.Value("A. Photographer, B. Assistant"), fields.creator)
        assertEquals(Detail.Value("© 2026 A. Photographer"), fields.copyright)
        assertEquals(Detail.Missing, fields.description)
    }

    @Test
    fun `video locations in ISO 6709`() {
        val point = parseIso6709("+35.6895+139.6917/", locationAllowed = true) as Detail.Value<GeoPoint>
        assertEquals(GeoPoint(35.6895, 139.6917), point.value)
        assertEquals(Detail.NoPermission, parseIso6709("+35.6895+139.6917/", locationAllowed = false))
        assertEquals(Detail.Missing, parseIso6709(null, locationAllowed = true))
        assertEquals(Detail.Unreadable, parseIso6709("somewhere", locationAllowed = true))
    }

    @Test
    fun `a file that is not an image yields no values`() {
        val fields = "not an image".byteInputStream().use { readPhotoFields(it, locationAllowed = true) }
        assertTrue(listOf(fields.takenAt, fields.camera, fields.location).none { it is Detail.Value })
    }
}
