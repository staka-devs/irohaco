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

enum class TimelineSortKey { TAKEN, NAME, SIZE }

/** The order and filter of the timeline (PRD GAL-006). Taken time falls back to the modification time. */
data class TimelineQuery(
    val sortKey: TimelineSortKey = TimelineSortKey.TAKEN,
    val descending: Boolean = true,
    /** Null shows photos and videos. */
    val kind: MediaKind? = null,
)

/**
 * Offset paging over a stable order: the sort key, then the asset id in the same direction. Offsets
 * let the UI refresh around the visible position when the catalog changes, so scrolling does not jump.
 */
interface TimelineReader {
    suspend fun count(query: TimelineQuery): Int

    suspend fun page(query: TimelineQuery, offset: Int, limit: Int): List<TimelineItem>

    /** The offset of an asset in that order, or null when it is not on the timeline. */
    suspend fun indexOf(query: TimelineQuery, assetId: String): Int?

    /** Emits whenever the catalog behind the timeline may have changed. */
    fun changes(): Flow<Unit>
}

enum class FileOperationResult { DONE, NOT_FOUND, NAME_TAKEN, FAILED }

/**
 * Moves, copies and renames files in the media library (GAL-005). Move and rename need the user's
 * write grant first; the caller asks for it. Folders are relative paths such as "DCIM/Camera/".
 */
interface MediaFiles {
    /** Folders in the library that accept both photos and videos. */
    suspend fun folders(): List<String>

    /** The current file name, or null when the file is gone. */
    suspend fun name(contentUri: String): String?

    suspend fun move(contentUri: String, folder: String): FileOperationResult

    /** The copy is a new file, so the next sync finds it as a new asset (UC-ID-01). */
    suspend fun copy(contentUri: String, folder: String): FileOperationResult

    /** [fileName] is the whole new name, extension included. */
    suspend fun rename(contentUri: String, fileName: String): FileOperationResult
}

/** Reads EXIF, XMP and container metadata of one file when it is asked for (PRD MET-001). */
fun interface MediaDetailsReader {
    suspend fun read(contentUri: String, kind: MediaKind): MediaDetailsResult
}
