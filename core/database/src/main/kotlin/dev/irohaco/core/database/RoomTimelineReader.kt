package dev.irohaco.core.database

import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineReader
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTimelineReader(private val db: IrohacoDatabase) : TimelineReader {
    override suspend fun count(): Int = db.timelineDao().count()

    override suspend fun page(offset: Int, limit: Int): List<TimelineItem> =
        db.timelineDao().page(offset, limit).map {
            TimelineItem(it.assetId, MediaKind.valueOf(it.mediaKind), it.contentUri, it.sortTimeMs)
        }

    override suspend fun indexOf(assetId: String): Int? = db.timelineDao().indexOf(assetId)

    /**
     * Emits once on subscription as well: a sync that finishes after the first page loads but before
     * this flow is collected would otherwise leave the timeline showing stale rows.
     */
    override fun changes(): Flow<Unit> =
        db.invalidationTracker.createFlow("media_sources", "media_assets", emitInitialState = true).map { }
}
