package dev.irohaco.feature.timeline.viewer

import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.domain.GeoPoint
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test
import kotlin.test.assertEquals

/** What the edit dialogs of the details sheet save (PRD MET-002). */
class MetadataEditInputTest {
    @Test
    fun `a location needs two numbers on Earth, and two blanks remove it`() {
        assertEquals(FieldEdit.Set(GeoPoint(35.68, 139.76)), parseLocation(" 35.68", "139.76 "))
        assertEquals(FieldEdit.Removed, parseLocation("", " "))
        assertEquals(null, parseLocation("35.68", ""))
        assertEquals(null, parseLocation("91", "0"))
        assertEquals(null, parseLocation("0", "-180.5"))
        assertEquals(null, parseLocation("north", "0"))
    }

    @Test
    fun `the picked day and time keep the seconds`() {
        assertEquals(1_761_701_445_000, takenAt(LocalDate.of(2025, 10, 29), 1, 30, 45, ZoneOffset.UTC))
    }
}
