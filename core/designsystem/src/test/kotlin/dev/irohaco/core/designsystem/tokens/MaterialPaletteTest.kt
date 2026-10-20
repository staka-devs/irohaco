package dev.irohaco.core.designsystem.tokens

import dev.irohaco.core.designsystem.theme.DefaultSeedColor
import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialPaletteTest {
    private val labels = listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")

    @Test
    fun `every hue has the fourteen shades in order`() {
        assertEquals(19, MaterialPalette.size)
        MaterialPalette.forEach { hue -> assertEquals(labels, hue.shades.map { it.label }) }
    }

    @Test
    fun `blue 500 is the default seed`() {
        assertEquals(DefaultSeedColor, MaterialPalette[5].main)
    }
}
