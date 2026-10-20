package dev.irohaco.feature.timeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.TimelineLabel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals

/** The fast scrollbar's bubble text (PRD GAL-014): the date, or whatever field the grid is sorted on. */
@RunWith(RobolectricTestRunner::class)
class FastScrollLabelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM")

    @Test
    fun `shows the group's date when sorted by date`() {
        val epochMs = Instant.parse("2026-07-15T00:00:00Z").toEpochMilli()
        assertEquals("2026-07", TimelineLabel.Taken(epochMs).format(formatter, ZoneOffset.UTC, context))
    }

    @Test
    fun `shows the sorted field otherwise`() {
        assertEquals("B", TimelineLabel.Name('B').format(formatter, ZoneOffset.UTC, context))
        assertEquals("★★★", TimelineLabel.Rating(3).format(formatter, ZoneOffset.UTC, context))
        assertEquals("☆", TimelineLabel.Rating(0).format(formatter, ZoneOffset.UTC, context))
    }
}
