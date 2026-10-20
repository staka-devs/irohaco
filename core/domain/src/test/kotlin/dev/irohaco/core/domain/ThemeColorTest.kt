package dev.irohaco.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThemeColorTest {
    @Test
    fun `parses six hex digits with or without the hash`() {
        assertEquals(ThemeColor.Seed(0x2196F3), ThemeColor.Seed.parseHex("#2196f3"))
        assertEquals(ThemeColor.Seed(0x2196F3), ThemeColor.Seed.parseHex(" 2196F3 "))
        assertEquals("#2196F3", ThemeColor.Seed(0x2196F3).toHex())
    }

    @Test
    fun `rejects anything that is not six hex digits`() {
        listOf("", "#", "#12345", "#1234567", "#12345G", "blue", "#-12345").forEach { assertNull(ThemeColor.Seed.parseHex(it), it) }
    }
}
