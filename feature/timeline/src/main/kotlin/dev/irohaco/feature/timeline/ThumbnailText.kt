package dev.irohaco.feature.timeline

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.domain.MediaKind
import java.util.Locale

/**
 * The grid marks GIFs only (GAL-016).
 * shortcut: an animated WebP gets no mark, as telling it from a still one means reading the file; add it if users miss it.
 */
internal val TimelineItem.isGif: Boolean get() = mimeType == "image/gif"

/** The format's own name, the same in every language. */
internal const val GIF_LABEL = "GIF"

/** The text in a cell's corner: "GIF", or a video's length (M1-26). */
internal val TimelineItem.badge: String?
    get() = when {
        kind == MediaKind.VIDEO -> durationMs?.let(::formatDuration)
        isGif -> GIF_LABEL
        else -> null
    }

/** A video's play mark, shown even when its length is unknown. */
@get:DrawableRes
internal val TimelineItem.badgeIcon: Int? get() = if (kind == MediaKind.VIDEO) IrohacoIcons.PlayArrowFilled else null

/** A length the way players show it: 0:46, 12:05, 1:01:19. */
internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val (hours, minutes, seconds) = Triple(total / 3600, total % 3600 / 60, total % 60)
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

/** What TalkBack reads for a grid cell: the kind, a video's length in words, then the date. */
@Composable
internal fun TimelineItem.description(date: String): String {
    val duration = durationMs
    if (kind != MediaKind.VIDEO || duration == null) return stringResource(descriptionRes(), date)
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(R.string.timeline_video_length_description, spokenDuration(duration, locale), date)
}

/** "1 minute, 1 second" rather than "1:01", which a screen reader may read as a time of day. */
internal fun spokenDuration(ms: Long, locale: Locale): String {
    val total = ms / 1000
    val parts = listOf(total / 3600 to MeasureUnit.HOUR, total % 3600 / 60 to MeasureUnit.MINUTE, total % 60 to MeasureUnit.SECOND)
        .filter { it.first > 0 }
        .ifEmpty { listOf(0L to MeasureUnit.SECOND) }
        .map { Measure(it.first, it.second) }
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).formatMeasures(*parts.toTypedArray())
}

/** What TalkBack reads for a grid cell: the kind, then the date (a "%1$s" argument). */
@StringRes
internal fun TimelineItem.descriptionRes(): Int = when {
    kind == MediaKind.VIDEO -> R.string.timeline_video_description
    isGif -> R.string.timeline_animated_description
    else -> R.string.timeline_photo_description
}
