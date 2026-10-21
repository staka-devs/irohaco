package dev.irohaco.feature.timeline

import androidx.annotation.StringRes
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaKind

/**
 * The grid marks GIFs only (GAL-016).
 * shortcut: an animated WebP gets no mark, as telling it from a still one means reading the file; add it if users miss it.
 */
internal val TimelineItem.isGif: Boolean get() = mimeType == "image/gif"

/** The format's own name, the same in every language. */
internal const val GIF_LABEL = "GIF"

/** What TalkBack reads for a grid cell: the kind, then the date (a "%1$s" argument). */
@StringRes
internal fun TimelineItem.descriptionRes(): Int = when {
    kind == MediaKind.VIDEO -> R.string.timeline_video_description
    isGif -> R.string.timeline_animated_description
    else -> R.string.timeline_photo_description
}
