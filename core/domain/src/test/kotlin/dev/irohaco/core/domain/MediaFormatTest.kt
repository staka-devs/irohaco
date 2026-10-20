package dev.irohaco.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaFormatTest {
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
