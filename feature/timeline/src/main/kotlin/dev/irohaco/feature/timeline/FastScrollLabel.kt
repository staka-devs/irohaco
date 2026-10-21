package dev.irohaco.feature.timeline

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.irohaco.core.contracts.TimelineLabel
import kotlinx.coroutines.flow.conflate
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

/**
 * The bubble's text while dragging. Each lookup runs to the end and the next one asks for the latest
 * position, so a finger that never stops still sees the date change (M1-35); cancelling the lookup on
 * every move left the bubble stuck until the finger paused.
 */
@Composable
internal fun rememberScrubLabel(dragIndex: Int?, lookup: suspend (Int) -> String?): State<String?> {
    val index by rememberUpdatedState(dragIndex)
    val currentLookup by rememberUpdatedState(lookup)
    return produceState<String?>(null) {
        snapshotFlow { index }.conflate().collect { value = it?.let { i -> currentLookup(i) } }
    }
}
