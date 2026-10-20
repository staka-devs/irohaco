package dev.irohaco.feature.timeline

import android.content.Context
import android.text.format.Formatter
import dev.irohaco.core.contracts.TimelineLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the fast scrollbar's bubble shows while dragging (PRD GAL-014): the date, or the sorted field. */
internal fun TimelineLabel.format(dateFormatter: DateTimeFormatter, zone: ZoneId, context: Context): String = when (this) {
    is TimelineLabel.Taken -> dateFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    is TimelineLabel.Name -> firstChar.toString()
    is TimelineLabel.Size -> Formatter.formatShortFileSize(context, bytes)
    is TimelineLabel.Rating -> if (rating <= 0) "☆" else "★".repeat(rating)
}
