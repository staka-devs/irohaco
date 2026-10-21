package dev.irohaco.core.application

import dev.irohaco.core.contracts.CollectionsStore
import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.core.contracts.MarksStore
import dev.irohaco.core.contracts.MediaDetailsReader
import dev.irohaco.core.contracts.MediaFiles
import dev.irohaco.core.contracts.MediaLibraryAccessPort
import dev.irohaco.core.contracts.ShareCandidate
import dev.irohaco.core.contracts.ShareFiles
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineLabel
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineReader
import dev.irohaco.core.contracts.TrashReader
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.MediaDetailsResult
import dev.irohaco.core.domain.MediaLibraryAccess
import dev.irohaco.core.domain.isValidName
import dev.irohaco.core.domain.renamedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the timeline a page at a time (PRD GAL-001) in the order and filter the user picked
 * (GAL-006). The timeline and the viewer share this choice for the session, so swiping in the
 * viewer follows the grid.
 */
class GetTimelinePage(private val reader: TimelineReader, private val clock: () -> Long = System::currentTimeMillis) {
    val query = MutableStateFlow(TimelineQuery())

    suspend fun count(query: TimelineQuery = this.query.value) = reader.count(query)

    suspend operator fun invoke(offset: Int, limit: Int, query: TimelineQuery = this.query.value) = reader.page(query, offset, limit)

    suspend fun indexOf(assetId: String, query: TimelineQuery = this.query.value) = reader.indexOf(query, assetId)

    suspend fun between(fromMs: Long, untilMs: Long, query: TimelineQuery = this.query.value) = reader.between(query, fromMs, untilMs)

    /**
     * The fast scrollbar's bubble at a position. The whole order's labels load in one query and stay
     * for a short while, so a moving finger never waits on the database (M1-35).
     * shortcut: a file added while they are kept shifts the bubble by one; key them to changes() if it shows.
     */
    suspend fun labelAt(index: Int, query: TimelineQuery = this.query.value): TimelineLabel? {
        val labels = labelsLock.withLock {
            val kept = kept?.takeIf { it.query == query && clock() - it.atMs < LABELS_KEPT_MS }
            kept?.labels ?: reader.labels(query).also { this.kept = KeptLabels(query, it, clock()) }
        }
        return labels.getOrNull(index)
    }

    private class KeptLabels(val query: TimelineQuery, val labels: List<TimelineLabel>, val atMs: Long)

    private val labelsLock = Mutex()
    private var kept: KeptLabels? = null

    fun changes() = reader.changes()
}

/** The OS grant right now, so screens can offer to ask for it (ADR-0009). */
class GetLibraryAccess(private val access: MediaLibraryAccessPort) {
    operator fun invoke(): MediaLibraryAccess = access.current()
}

/**
 * Move, copy and rename (GAL-005). A batch stops at the first failure and reports it, so the user
 * sees what went wrong while the remaining items stay selected for another try.
 */
class OrganizeMedia(private val files: MediaFiles) {
    suspend fun folders() = files.folders()

    suspend fun currentName(item: TimelineItem) = files.name(item.contentUri)

    suspend fun move(items: List<TimelineItem>, folder: String) = forEach(items) { files.move(it.contentUri, folder) }

    suspend fun copy(items: List<TimelineItem>, folder: String) = forEach(items) { files.copy(it.contentUri, folder) }

    /** [base] is the name without the extension; the file keeps its own. */
    suspend fun rename(item: TimelineItem, base: String): FileOperationResult {
        require(isValidName(base)) { "not a file name: $base" }
        val current = files.name(item.contentUri) ?: return FileOperationResult.NOT_FOUND
        return files.rename(item.contentUri, renamedFile(current, base))
    }

    private suspend fun forEach(items: List<TimelineItem>, action: suspend (TimelineItem) -> FileOperationResult): FileOperationResult {
        for (item in items) {
            val result = action(item)
            if (result != FileOperationResult.DONE) return result
        }
        return FileOperationResult.DONE
    }
}

/** Favorites and ratings (PRD GAL-007). */
class MarkMedia(private val store: MarksStore) {
    fun marks(assetId: String) = store.marks(assetId)

    suspend fun setFavorite(items: List<TimelineItem>, favorite: Boolean) = store.setFavorite(items.map { it.assetId }, favorite)

    suspend fun setRating(assetId: String, rating: Int?) = store.setRating(assetId, rating)
}

/** Tags and user albums (PRD GAL-007). */
class ManageCollections(private val store: CollectionsStore) {
    fun tags() = store.tags()

    fun tagsOf(assetId: String) = store.tagsOf(assetId)

    suspend fun addTag(items: List<TimelineItem>, name: String) = store.addTag(items.map { it.assetId }, name)

    suspend fun removeTag(assetId: String, tagId: String) = store.removeTag(assetId, tagId)

    fun albums() = store.albums()

    suspend fun createAlbum(title: String) = store.createAlbum(title)

    suspend fun addToAlbum(albumId: String, items: List<TimelineItem>) = store.addToAlbum(albumId, items.map { it.assetId })
}

/** The details sheet's data, read from the file each time it opens (PRD MET-001). */
class GetMediaDetails(private val reader: MediaDetailsReader) {
    suspend operator fun invoke(item: TimelineItem) = reader.read(item.contentUri, item.kind)
}

/** Shares with or without GPS, as the user's settings or in-the-moment choice says (PRD GAL-005, MET-003). */
class PrepareShare(private val detailsReader: MediaDetailsReader, private val shareFiles: ShareFiles) {
    suspend fun check(items: List<TimelineItem>): List<ShareCandidate> = items.map { item ->
        val hasLocation = (detailsReader.read(item.contentUri, item.kind) as? MediaDetailsResult.Read)?.details?.location is Detail.Value
        ShareCandidate(item, hasLocation)
    }

    /**
     * A photo with GPS gets a cache copy, with or without it as asked; everything else (nothing to
     * redact, or a video, whose GPS this does not touch) shares its own file untouched.
     */
    suspend fun prepare(candidates: List<ShareCandidate>, stripLocation: Boolean): List<TimelineItem> = candidates.map { candidate ->
        if (!candidate.hasLocation || !candidate.locationRemovable) return@map candidate.item
        shareFiles.prepareCopy(candidate.item.contentUri, keepLocation = !stripLocation)?.let { candidate.item.copy(contentUri = it) } ?: candidate.item
    }

    suspend fun cleanUp() = shareFiles.cleanUp()
}

/** The trash (PRD GAL-015): MediaStore's own, so restoring or deleting for good go through the OS. */
class BrowseTrash(private val reader: TrashReader) {
    suspend fun list() = reader.list()
}

private const val LABELS_KEPT_MS = 30_000L
