package dev.irohaco.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaFormatTest {
    @Test
    fun `gif and animated webp are animated, a still webp is not`() {
        fun webp(flags: Int) = "RIFF\u0000\u0000\u0000\u0000WEBPVP8X".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 0, flags.toByte())
        assertEquals(true, isAnimatedImage("image/gif", ByteArray(0)))
        assertEquals(true, isAnimatedImage(null, "GIF89a".toByteArray()))
        assertEquals(true, isAnimatedImage("image/webp", webp(0x02)))
        assertEquals(false, isAnimatedImage("image/webp", webp(0x10)))
        assertEquals(false, isAnimatedImage("image/jpeg", byteArrayOf(-1, -40, -1)))
    }

    @Test
    fun `raw comes from the mime type or the extension`() {
        assertEquals(MediaFormat(raw = true), detectFormat("image/x-adobe-dng", "a.dng", null))
        assertEquals(MediaFormat(raw = true), detectFormat("application/octet-stream", "IMG_1.CR3", null))
        assertEquals(MediaFormat(), detectFormat("image/jpeg", "a.jpg", null))
    }

    @Test
    fun `ultra hdr and motion photo come from xmp`() {
        val ultraHdr = """<rdf:Description hdrgm:Version="1.0" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"/>"""
        assertEquals(MediaFormat(hdr = true), detectFormat("image/jpeg", "a.jpg", ultraHdr))
        assertEquals(MediaFormat(motionPhoto = true), detectFormat("image/jpeg", "a.jpg", """<x GCamera:MotionPhoto="1"/>"""))
        assertEquals(MediaFormat(motionPhoto = true), detectFormat("image/jpeg", "a.jpg", "<GCamera:MicroVideo>1</GCamera:MicroVideo>"))
        assertEquals(MediaFormat(), detectFormat("image/jpeg", "a.jpg", """<x GCamera:MotionPhoto="0"/>"""))
    }
}
