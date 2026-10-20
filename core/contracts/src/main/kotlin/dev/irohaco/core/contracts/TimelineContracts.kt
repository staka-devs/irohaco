package dev.irohaco.core.contracts

import dev.irohaco.core.domain.MediaFormat
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

enum class TimelineSortKey { TAKEN, NAME, SIZE, RATING }

/** A folder on one volume, such as "DCIM/Camera/"; the volume root is "" (PRD GAL-002). */
data class FolderRef(val volumeId: String, val relativePath: String)

/** The order and filter of the timeline (PRD GAL-006). Taken time falls back to the modification time. */
data class TimelineQuery(
    val sortKey: TimelineSortKey = TimelineSortKey.TAKEN,
    val descending: Boolean = true,
    /** Null shows photos and videos. */
    val kind: MediaKind? = null,
    /** Only this folder, without its subfolders; null shows the whole library. */
    val folder: FolderRef? = null,
    /** Folders the user hid from the timeline. */
    val excludedFolders: Set<FolderRef> = emptySet(),
    val favoritesOnly: Boolean = false,
    val tagId: String? = null,
    /** An album shows its items in the order they were added, whatever [sortKey] says. */
    val albumId: String? = null,
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

    /** Every item whose sort time is in [fromMs, untilMs), in the query's order: a whole date group. */
    suspend fun between(query: TimelineQuery, fromMs: Long, untilMs: Long): List<TimelineItem>

    /** Emits whenever the catalog behind the timeline may have changed. */
    fun changes(): Flow<Unit>
}

/** A folder that holds photos or videos, counted on the library screen (GAL-002, AC-010). */
data class LibraryFolder(
    val folder: FolderRef,
    /** MediaStore's volume name: "external_primary" is the built-in storage, others are SD cards. */
    val volumeName: String,
    /** The SD card is out; the folder and its count stay as they were when it was last seen. */
    val offline: Boolean,
    val itemCount: Int,
    /** The newest item of the folder, for the card in the library's grid view. */
    val coverUri: String? = null,
)

interface LibraryReader {
    suspend fun folders(): List<LibraryFolder>

    fun changes(): Flow<Unit>
}

/** What the user marked on an asset (PRD GAL-007). It belongs to the asset, so moving or unplugging the file keeps it. */
data class AssetMarks(val favorite: Boolean = false, val rating: Int? = null)

interface MarksStore {
    fun marks(assetId: String): Flow<AssetMarks>

    suspend fun setFavorite(assetIds: Collection<String>, favorite: Boolean)

    /** 1 to 5 stars, or null for no rating. */
    suspend fun setRating(assetId: String, rating: Int?)
}

data class Tag(val tagId: String, val name: String, val itemCount: Int)

data class Album(val albumId: String, val title: String, val itemCount: Int, val coverUri: String?)

/** Tags and user albums (PRD GAL-007). Both point at assets, so they survive moves and unplugged cards. */
interface CollectionsStore {
    fun tags(): Flow<List<Tag>>

    fun tagsOf(assetId: String): Flow<List<Tag>>

    /** Finds the tag by name or creates it, then puts it on every asset. */
    suspend fun addTag(assetIds: Collection<String>, name: String)

    suspend fun removeTag(assetId: String, tagId: String)

    /** Counts and covers come from available media only. */
    fun albums(): Flow<List<Album>>

    suspend fun createAlbum(title: String): String

    /** Appends in the given order; assets already in the album keep their place. */
    suspend fun addToAlbum(albumId: String, assetIds: List<String>)
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

/** Tells which special format a photo is (PRD GAL-009); unreadable files are plain ones. */
fun interface MediaFormatProbe {
    suspend fun probe(contentUri: String, kind: MediaKind): MediaFormat
}

/** Reads EXIF, XMP and container metadata of one file when it is asked for (PRD MET-001). */
fun interface MediaDetailsReader {
    suspend fun read(contentUri: String, kind: MediaKind): MediaDetailsResult
}

/** An item about to be shared, and whether its GPS could be removed from the shared copy (PRD MET-003). */
data class ShareCandidate(val item: TimelineItem, val hasLocation: Boolean) {
    /** Only photos can have their GPS stripped; videos keep whatever location they have (M3-04). */
    val locationRemovable: Boolean get() = item.kind == MediaKind.IMAGE

    val cannotRemoveLocation: Boolean get() = hasLocation && !locationRemovable
}

/**
 * Makes a copy of a photo with its GPS removed, for sharing without the original (PRD GAL-005, MET-003).
 * The original file is never changed; the copy lives in the app's own cache, cleaned up on its own.
 */
interface ShareFiles {
    /**
     * A content:// URI of the copy, usable in ACTION_SEND; null if the copy could not be made.
     * Even when [keepLocation] is true, the receiving app's own read of the original would normally
     * have its GPS redacted by the OS, so a copy is made either way, with or without it.
     */
    suspend fun prepareCopy(contentUri: String, keepLocation: Boolean): String?

    /** Deletes copies past the app's retention window; the caller runs this once per app start. */
    suspend fun cleanUp()
}
