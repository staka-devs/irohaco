package dev.irohaco.infrastructure.media

import dev.irohaco.core.domain.Detail
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class IptcParsingTest {
    private fun dataset(record: Int, number: Int, value: ByteArray) =
        byteArrayOf(0x1C, record.toByte(), number.toByte(), (value.size shr 8).toByte(), value.size.toByte()) + value

    private fun text(number: Int, value: String) = dataset(2, number, value.toByteArray(Charsets.UTF_8))

    /** SOI, an APP1 to skip, the Photoshop APP13 with the IPTC block, then the image data marker. */
    private fun jpeg(iim: ByteArray, resourceName: String = ""): ByteArray {
        val name = byteArrayOf(resourceName.length.toByte()) + resourceName.toByteArray(Charsets.US_ASCII)
        val paddedName = if (name.size % 2 == 1) name + 0 else name
        val block = if (iim.size % 2 == 1) iim + 0 else iim
        val photoshop = "Photoshop 3.0\u0000".toByteArray(Charsets.US_ASCII) + "8BIM".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x04, 0x04) + paddedName +
            byteArrayOf((iim.size shr 24).toByte(), (iim.size shr 16).toByte(), (iim.size shr 8).toByte(), iim.size.toByte()) + block
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            segment(0xE1, "Exif\u0000\u0000junk".toByteArray(Charsets.US_ASCII))
            segment(0xED, photoshop)
            write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.segment(type: Int, payload: ByteArray) {
        val length = payload.size + 2
        write(byteArrayOf(0xFF.toByte(), type.toByte(), (length shr 8).toByte(), length.toByte()))
        write(payload)
    }

    @Test
    fun `reads the headline, caption, keywords, creators and copyright`() {
        val iim = dataset(1, 90, byteArrayOf(0x1B, 0x25, 0x47)) +
            text(105, "港の朝") + text(120, "Fishing boats at dawn") + text(25, "harbour") + text(25, "dawn") +
            text(80, "A. Photographer") + text(80, "B. Assistant") + text(116, "© 2026 A. Photographer")
        val fields = readIptc(jpeg(iim, resourceName = "odd").inputStream())
        assertEquals(
            IptcFields(
                headline = Detail.Value("港の朝"),
                caption = Detail.Value("Fishing boats at dawn"),
                keywords = Detail.Value(listOf("harbour", "dawn")),
                creator = Detail.Value("A. Photographer, B. Assistant"),
                copyright = Detail.Value("© 2026 A. Photographer"),
            ),
            fields,
        )
    }

    @Test
    fun `latin-1 text without a declared character set`() {
        val fields = readIptc(jpeg(dataset(2, 120, "Café".toByteArray(Charsets.ISO_8859_1))).inputStream())
        assertEquals(Detail.Value("Café"), fields.caption)
    }

    @Test
    fun `no IPTC is missing, a broken block is unreadable, other formats are missing`() {
        assertEquals(IptcFields.missing, readIptc(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte()).inputStream()))
        assertEquals(IptcFields.unreadable, readIptc(jpeg(byteArrayOf(0x1C, 2, 120, 0x7F, 0x00, 1, 2)).inputStream()))
        assertEquals(IptcFields.missing, readIptc("\u0089PNG".toByteArray(Charsets.ISO_8859_1).inputStream()))
    }

    @Test
    fun `XMP wins, IPTC fills the gaps`() {
        assertEquals(Detail.Value("xmp"), preferXmp(Detail.Value("xmp"), Detail.Value("iptc")))
        assertEquals(Detail.Value("iptc"), preferXmp(Detail.Missing, Detail.Value("iptc")))
        assertEquals(Detail.Value("iptc"), preferXmp(null, Detail.Value("iptc")))
        assertEquals(Detail.Unreadable, preferXmp(Detail.Unreadable, Detail.Missing))
        assertEquals(Detail.Missing, preferXmp<String>(null, Detail.Missing))
    }
}
