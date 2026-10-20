package dev.irohaco.core.contracts

import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.MediaDetailsResult
import kotlinx.coroutines.flow.Flow

/** One cell of the timeline (PRD GAL-001): an available photo or video, newest first. */
data class TimelineItem(
    val assetId: String,
    val kind: MediaKind,
    val contentUri: String,
    /** Capture time, or the modification time when MediaStore has no capture time. */
    val sortTimeMs: Long,
)

/**
 * Offset paging over a stable order (sort time, then asset id, newest first). Offsets let the UI
 * refresh around the visible position when the catalog changes, so scrolling does not jump.
 */
interface TimelineReader {
    suspend fun count(): Int

    suspend fun page(offset: Int, limit: Int): List<TimelineItem>

    /** The offset of an asset in that order, or null when it is not on the timeline. */
    suspend fun indexOf(assetId: String): Int?

    /** Emits whenever the catalog behind the timeline may have changed. */
    fun changes(): Flow<Unit>
}

/** Reads EXIF, XMP and container metadata of one file when it is asked for (PRD MET-001). */
fun interface MediaDetailsReader {
    suspend fun read(contentUri: String, kind: MediaKind): MediaDetailsResult
}
