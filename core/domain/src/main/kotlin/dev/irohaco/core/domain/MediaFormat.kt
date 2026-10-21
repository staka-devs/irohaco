package dev.irohaco.core.domain

/** Formats the viewer shows with a label, because it cannot show all of them in full (PRD GAL-009). */
data class MediaFormat(
    val raw: Boolean = false,
    val hdr: Boolean = false,
    val motionPhoto: Boolean = false,
    /** A GIF or an animated WebP, which the viewer plays (GAL-016). */
    val animated: Boolean = false,
)

/**
 * A GIF, or a WebP whose extended header (VP8X) sets the animation flag, from the file's first bytes.
 * shortcut: every GIF counts, even a single-frame one; it then plays as a still, which is harmless.
 */
fun isAnimatedImage(mimeType: String?, header: ByteArray): Boolean {
    fun ascii(at: Int, text: String) = header.size >= at + text.length && text.indices.all { header[at + it] == text[it].code.toByte() }
    return when {
        mimeType == "image/gif" || ascii(0, "GIF8") -> true
        ascii(0, "RIFF") && ascii(8, "WEBPVP8X") && header.size > 20 -> header[20].toInt() and 0x02 != 0
        else -> false
    }
}

private val RAW_MIME_TYPES = setOf(
    "image/x-adobe-dng", "image/dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-nikon-nef", "image/x-nikon-nrw",
    "image/x-sony-arw", "image/x-fuji-raf", "image/x-olympus-orf", "image/x-panasonic-rw2", "image/x-pentax-pef", "image/x-samsung-srw",
)
private val RAW_EXTENSIONS = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "raf", "orf", "rw2", "pef", "srw")

// The Motion Photo marks of XMP, written as an attribute (GCamera:MotionPhoto="1") or an element (<...>1</...>).
private val MOTION_PHOTO = Regex("""(?:MotionPhoto|MicroVideo)\s*(?:=\s*["']1["']|>\s*1\s*<)""")

/**
 * RAW from the MIME type or the extension; HDR from the Ultra HDR gain map's XMP (hdrgm:Version);
 * Motion Photo from its XMP marks. A missing XMP packet only means none of the latter two.
 */
fun detectFormat(mimeType: String?, fileName: String?, xmp: String?): MediaFormat = MediaFormat(
    raw = mimeType?.lowercase() in RAW_MIME_TYPES || fileName?.substringAfterLast('.', "")?.lowercase() in RAW_EXTENSIONS,
    hdr = xmp?.contains("hdrgm:Version") == true,
    motionPhoto = xmp?.let { MOTION_PHOTO.containsMatchIn(it) } == true,
)
