package dev.irohaco.core.application

import dev.irohaco.core.contracts.MediaLibraryAccessPort
import dev.irohaco.core.contracts.TimelineReader
import dev.irohaco.core.domain.MediaLibraryAccess

/** Reads the timeline a page at a time (PRD GAL-001). */
class GetTimelinePage(private val reader: TimelineReader) {
    suspend fun count() = reader.count()

    suspend operator fun invoke(offset: Int, limit: Int) = reader.page(offset, limit)

    fun changes() = reader.changes()
}

/** The OS grant right now, so screens can offer to ask for it (ADR-0009). */
class GetLibraryAccess(private val access: MediaLibraryAccessPort) {
    operator fun invoke(): MediaLibraryAccess = access.current()
}
