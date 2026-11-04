package dev.irohaco.feature.settings

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The license list the build records reads into one row per library, with its text (ADR-0033). */
@RunWith(RobolectricTestRunner::class) // the library logs through android.util.Log
class AboutSettingsTest {
    @Test
    fun `each library comes with its license text, or the address of terms that have none`() {
        val json = """
            {"libraries": [
              {"uniqueId": "x:zeta", "artifactVersion": "2", "name": "Zeta", "licenses": ["mit"], "developers": [], "funding": []},
              {"uniqueId": "x:alpha", "artifactVersion": "1", "name": "alpha", "licenses": ["terms"], "developers": [], "funding": []}
            ],
            "licenses": {
              "mit": {"hash": "mit", "name": "MIT License", "url": "https://opensource.org/licenses/MIT", "content": "Permission is hereby granted"},
              "terms": {"hash": "terms", "name": "Some Terms", "url": "https://example.com/terms"}
            }}
        """.trimIndent()
        assertEquals(
            listOf(
                LibraryLicense("alpha", "1", listOf("Some Terms"), "Some Terms\nhttps://example.com/terms"),
                LibraryLicense("Zeta", "2", listOf("MIT License"), "Permission is hereby granted"),
            ),
            parseLicenses(json),
        )
    }

    @Test
    fun `an unreadable list shows no libraries rather than failing the screen`() {
        assertEquals(emptyList(), parseLicenses("not json"))
        assertEquals(emptyList(), parseLicenses(""))
    }
}

