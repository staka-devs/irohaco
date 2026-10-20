package dev.irohaco.infrastructure.media

import dev.irohaco.core.domain.Detail
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The IPTC-IIM fields of the details sheet (PRD MET-001). XMP wins where both have a value. */
internal data class IptcFields(
    val headline: Detail<String>,
    val caption: Detail<String>,
    val keywords: Detail<List<String>>,
    val creator: Detail<String>,
    val copyright: Detail<String>,
) {
    companion object {
        val missing = IptcFields(Detail.Missing, Detail.Missing, Detail.Missing, Detail.Missing, Detail.Missing)
        val unreadable = IptcFields(Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable, Detail.Unreadable)
    }
}

private const val SOI = 0xFFD8
private const val APP13 = 0xED
private const val SOS = 0xDA
private const val EOI = 0xD9
private const val IPTC_RESOURCE = 0x0404
private val PHOTOSHOP = "Photoshop 3.0\u0000".toByteArray(Charsets.US_ASCII)

/**
 * Walks the JPEG header segments up to the image data, so a large photo is not read in full, and
 * reads the IPTC block of the Photoshop APP13 segment. Other formats have no IPTC here.
 */
internal fun readIptc(input: InputStream): IptcFields {
    val data = DataInputStream(BufferedInputStream(input))
    return try {
        if (data.readUnsignedShort() != SOI) return IptcFields.missing
        while (true) {
            if (data.readUnsignedByte() != 0xFF) return IptcFields.unreadable
            var type = data.readUnsignedByte()
            while (type == 0xFF) type = data.readUnsignedByte() // fill bytes
            if (type == SOS || type == EOI) return IptcFields.missing
            val length = data.readUnsignedShort() - 2
            if (length < 0) return IptcFields.unreadable
            val segment = ByteArray(length).also { data.readFully(it) }
            if (type == APP13) parsePhotoshop(segment)?.let { return it }
        }
        IptcFields.missing
    } catch (e: EOFException) {
        IptcFields.unreadable
    } catch (e: IOException) {
        IptcFields.unreadable
    }
}

/** Null when the segment is not Photoshop's, so the walk goes on. */
private fun parsePhotoshop(segment: ByteArray): IptcFields? {
    if (segment.size < PHOTOSHOP.size || !segment.copyOf(PHOTOSHOP.size).contentEquals(PHOTOSHOP)) return null
    val buffer = ByteBuffer.wrap(segment, PHOTOSHOP.size, segment.size - PHOTOSHOP.size)
    try {
        while (buffer.remaining() >= 12) {
            val signature = ByteArray(4).also { buffer.get(it) }
            if (!signature.contentEquals("8BIM".toByteArray(Charsets.US_ASCII))) return IptcFields.unreadable
            val id = buffer.short.toInt() and 0xFFFF
            val nameLength = buffer.get().toInt() and 0xFF
            buffer.position(buffer.position() + nameLength + (if ((nameLength + 1) % 2 == 1) 1 else 0))
            val size = buffer.int
            if (size < 0 || size > buffer.remaining()) return IptcFields.unreadable
            val block = ByteArray(size).also { buffer.get(it) }
            if (size % 2 == 1 && buffer.hasRemaining()) buffer.get()
            if (id == IPTC_RESOURCE) return parseIim(block)
        }
    } catch (e: RuntimeException) {
        return IptcFields.unreadable // BufferUnderflowException or a bad position: a broken block
    }
    return IptcFields.missing
}

/** Datasets of record 2 (application); record 1 dataset 90 names the character set. */
private fun parseIim(block: ByteArray): IptcFields {
    val values = mutableMapOf<Int, MutableList<ByteArray>>()
    var utf8 = false
    var i = 0
    while (i + 5 <= block.size) {
        if (block[i] != 0x1C.toByte()) return IptcFields.unreadable
        val record = block[i + 1].toInt() and 0xFF
        val dataset = block[i + 2].toInt() and 0xFF
        val length = ((block[i + 3].toInt() and 0xFF) shl 8) or (block[i + 4].toInt() and 0xFF)
        // shortcut: extended (over 32 KB) datasets are not read; no text field of these needs one.
        if (length and 0x8000 != 0 || i + 5 + length > block.size) return IptcFields.unreadable
        val value = block.copyOfRange(i + 5, i + 5 + length)
        if (record == 1 && dataset == 90) utf8 = value.contentEquals(byteArrayOf(0x1B, 0x25, 0x47)) // ESC % G
        if (record == 2) values.getOrPut(dataset) { mutableListOf() } += value
        i += 5 + length
    }
    fun texts(dataset: Int) = values[dataset].orEmpty().map { decode(it, utf8).trim() }.filter { it.isNotEmpty() }
    fun one(dataset: Int): Detail<String> = texts(dataset).firstOrNull()?.let { Detail.Value(it) } ?: Detail.Missing
    return IptcFields(
        headline = one(105),
        caption = one(120),
        keywords = texts(25).takeIf { it.isNotEmpty() }?.let { Detail.Value(it) } ?: Detail.Missing,
        creator = texts(80).takeIf { it.isNotEmpty() }?.let { Detail.Value(it.joinToString(", ")) } ?: Detail.Missing,
        copyright = one(116),
    )
}

/** UTF-8 when declared or when the bytes are valid UTF-8; older files are Latin-1. */
private fun decode(bytes: ByteArray, utf8: Boolean): String {
    if (utf8) return bytes.decodeToString()
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charsets.ISO_8859_1)
    }
}

/** XMP first; IPTC only fills what XMP does not have (#83). */
internal fun <T> preferXmp(xmp: Detail<T>?, iptc: Detail<T>): Detail<T> = when {
    xmp is Detail.Value -> xmp
    iptc is Detail.Value -> iptc
    xmp == Detail.Unreadable || iptc == Detail.Unreadable -> Detail.Unreadable
    else -> Detail.Missing
}
