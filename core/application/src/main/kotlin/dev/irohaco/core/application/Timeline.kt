package dev.irohaco.core.application

import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.core.contracts.MediaDetailsReader
import dev.irohaco.core.contracts.MediaFiles
import dev.irohaco.core.contracts.MediaLibraryAccessPort
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineReader
import dev.irohaco.core.domain.MediaLibraryAccess
import dev.irohaco.core.domain.isValidName
import dev.irohaco.core.domain.renamedFile
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Reads the timeline a page at a time (PRD GAL-001) in the order and filter the user picked
 * (GAL-006). The timeline and the viewer share this choice for the session, so swiping in the
 * viewer follows the grid.
 */
class GetTimelinePage(private val reader: TimelineReader) {
    val query = MutableStateFlow(TimelineQuery())

    suspend fun count(query: TimelineQuery = this.query.value) = reader.count(query)

    suspend operator fun invoke(offset: Int, limit: Int, query: TimelineQuery = this.query.value) = reader.page(query, offset, limit)

    suspend fun indexOf(assetId: String, query: TimelineQuery = this.query.value) = reader.indexOf(query, assetId)

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

/** The details sheet's data, read from the file each time it opens (PRD MET-001). */
class GetMediaDetails(private val reader: MediaDetailsReader) {
    suspend operator fun invoke(item: TimelineItem) = reader.read(item.contentUri, item.kind)
}
