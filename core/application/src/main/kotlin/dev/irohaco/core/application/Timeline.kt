package dev.irohaco.core.application

import dev.irohaco.core.contracts.CollectionsStore
import dev.irohaco.core.contracts.DeletedMediaStore
import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.MarksStore
import dev.irohaco.core.contracts.MetadataEditStore
import dev.irohaco.core.contracts.MetadataEdits
import dev.irohaco.core.contracts.MetadataField
import dev.irohaco.core.contracts.MetadataFiles
import dev.irohaco.core.contracts.MetadataWriteJournal
import dev.irohaco.core.contracts.MetadataWriteState
import dev.irohaco.core.contracts.toMetadataText
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
import dev.irohaco.core.contracts.TrashedItem
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaDetails
import dev.irohaco.core.domain.MediaDetailsResult
import dev.irohaco.core.domain.MediaFormat
import dev.irohaco.core.domain.MediaLibraryAccess
import dev.irohaco.core.domain.isValidName
import dev.irohaco.core.domain.renamedFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

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

/** Capture time, location and description edited in the DB only; the file stays as it is (PRD MET-002). */
class EditMetadata(private val store: MetadataEditStore) {
    fun edits(assetId: String) = store.edits(assetId)

    suspend fun setTakenAt(assetId: String, takenAtMs: Long?) = store.setTakenAt(assetId, takenAtMs)

    suspend fun setLocation(assetId: String, edit: FieldEdit<GeoPoint>?) = store.setLocation(assetId, edit)

    suspend fun setDescription(assetId: String, edit: FieldEdit<String>?) = store.setDescription(assetId, edit)

    /**
     * Checks the file's values just read against the edits (INT-002). Values the file withholds or could not
     * give are left out, so they are never taken for a change. The edited time is compared in [zone], where it was picked.
     */
    suspend fun compareWithFile(assetId: String, file: MediaDetails, zone: ZoneId = ZoneId.systemDefault()) {
        val edits = store.edits(assetId).first()
        val read = buildMap {
            putRead(MetadataField.TAKEN, file.takenAt) { it.toString() }
            putRead(MetadataField.LOCATION, file.location) { it.toMetadataText() }
            putRead(MetadataField.DESCRIPTION, file.description) { it }
        }
        store.compareWithFile(assetId, read, edits.asText(zone))
    }

    suspend fun keepEdit(assetId: String, field: MetadataField) = store.keepEdit(assetId, field)

    private fun <T> MutableMap<MetadataField, String?>.putRead(field: MetadataField, detail: Detail<T>, format: (T) -> String) {
        when (detail) {
            is Detail.Value -> put(field, format(detail.value))
            Detail.Missing -> put(field, null)
            else -> Unit
        }
    }
}

/** The edited fields as the text the store compares and the file takes, null for a removed value. */
private fun MetadataEdits.asText(zone: ZoneId): Map<MetadataField, String?> = buildMap {
    takenAtMs?.let { put(MetadataField.TAKEN, Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().toString()) }
    location?.let { put(MetadataField.LOCATION, (it as? FieldEdit.Set)?.value?.toMetadataText()) }
    description?.let { put(MetadataField.DESCRIPTION, (it as? FieldEdit.Set)?.value) }
}

/** How a write of edits to the file ended (AC-008). */
enum class FileWriteResult {
    WRITTEN,

    /** The file was never touched; the edits stay in the DB. */
    NOT_WRITTEN,

    /** The file was replaced but did not read back as written; the user restores or keeps it. */
    CHECK_FAILED,
}

/**
 * Writes the DB edits into the file itself or into a copy (MET-003, ADR-0030), journaled so a stopped process
 * leaves a way back (ADR-0004). A write to the file needs the OS write grant, which the caller asks for first.
 */
class WriteMetadataToFile(
    private val store: MetadataEditStore,
    private val journal: MetadataWriteJournal,
    private val files: MetadataFiles,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** A JPEG with no video or gain map after the image, whose place a longer header would move (ADR-0030). */
    fun canWriteFile(item: TimelineItem, format: MediaFormat) = item.mimeType == JPEG && !format.motionPhoto && !format.hdr

    /** The formats whose EXIF the app can write. */
    fun canSaveCopy(item: TimelineItem) = item.mimeType in setOf(JPEG, "image/png", "image/webp")

    private val settling = Mutex()
    private var settled = false

    /** The write of the asset whose file waits for the user to restore or keep it. */
    fun recoveryRequired(assetId: String): Flow<String?> = flow {
        settleStopped()
        emitAll(journal.recoveryRequired(assetId))
    }

    suspend fun write(item: TimelineItem, fields: Set<MetadataField>): FileWriteResult {
        settleStopped()
        val values = store.edits(item.assetId).first().asText(zone()).filterKeys { it in fields }
        if (values.isEmpty()) return FileWriteResult.NOT_WRITTEN
        val operation = journal.begin(item.assetId, values.keys)
        if (!files.stage(operation, item.contentUri, values)) {
            forget(operation)
            return FileWriteResult.NOT_WRITTEN
        }
        journal.advance(operation, MetadataWriteState.STAGED)
        journal.advance(operation, MetadataWriteState.WRITE_PENDING)
        val replaced = files.replace(operation, item.contentUri)
        journal.advance(operation, MetadataWriteState.VERIFYING)
        if (replaced && files.verify(operation, item.contentUri, values)) {
            journal.commit(operation, values)
            files.discard(operation)
            return FileWriteResult.WRITTEN
        }
        // A failed replace may still have truncated the file, so it is checked by the user either way.
        journal.advance(operation, MetadataWriteState.RECOVERY_REQUIRED)
        return FileWriteResult.CHECK_FAILED
    }

    /** Puts back the file as it was before the write; the edits stay in the DB. */
    suspend fun restore(item: TimelineItem, operationId: String): Boolean =
        files.restore(operationId, item.contentUri).also { if (it) forget(operationId) }

    /** Keeps the file as the write left it. */
    suspend fun keepFile(operationId: String) = forget(operationId)

    /**
     * Once a process, before its first write: a write a stopped process left before touching the file is dropped,
     * one it left after waits for the user. Later, unfinished writes are this process's own, still running.
     */
    private suspend fun settleStopped() = settling.withLock {
        if (settled) return@withLock
        settled = true
        journal.unfinished().forEach { (operation, state) ->
            if (state == MetadataWriteState.REQUESTED || state == MetadataWriteState.STAGED) {
                forget(operation)
            } else {
                journal.advance(operation, MetadataWriteState.RECOVERY_REQUIRED)
            }
        }
    }

    /** Saves a new file beside the original with all the edits and, if asked, without any location. */
    suspend fun saveCopy(item: TimelineItem, removeLocation: Boolean): Boolean =
        files.saveCopy(item.contentUri, store.edits(item.assetId).first().asText(zone()), removeLocation)

    private suspend fun forget(operationId: String) {
        journal.close(operationId)
        files.discard(operationId)
    }

    private companion object {
        const val JPEG = "image/jpeg"
    }
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

/**
 * The trash (PRD GAL-015): MediaStore's own, so restoring or deleting for good go through the OS. A file
 * deleted for good leaves nothing in the DB (ADR-0018); one only moved to the trash keeps everything.
 */
class BrowseTrash(private val reader: TrashReader, private val deleted: DeletedMediaStore) {
    suspend fun list() = reader.list()

    /** Call once the system confirmed the delete. */
    suspend fun deletedForever(items: List<TrashedItem>) = deleted.purge(items.map { it.contentUri })
}

private const val LABELS_KEPT_MS = 30_000L
