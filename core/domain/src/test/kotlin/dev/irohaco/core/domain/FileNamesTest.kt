package dev.irohaco.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileNamesTest {
    @Test
    fun `blank, separators and dot names are rejected`() {
        listOf("", "  ", "a/b", ".", "..", " .. ").forEach { assertFalse(isValidName(it), it) }
        listOf("trip", " trip 2026 ", "a.b", "旅行").forEach { assertTrue(isValidName(it), it) }
    }

    @Test
    fun `renaming keeps the extension`() {
        assertEquals("IMG_0420", baseName("IMG_0420.jpg"))
        assertEquals("harbour.jpg", renamedFile("IMG_0420.jpg", " harbour "))
        assertEquals("v1.2.mp4", renamedFile("clip.mp4", "v1.2"))
    }

    @Test
    fun `a leading dot or no dot is not an extension`() {
        assertEquals(".nomedia", baseName(".nomedia"))
        assertEquals("b", renamedFile(".nomedia", "b"))
        assertEquals("b", renamedFile("README", "b"))
    }

    @Test
    fun `new folders go under Pictures`() {
        assertEquals("Pictures/Trip/", newFolderPath(" Trip "))
    }
}
