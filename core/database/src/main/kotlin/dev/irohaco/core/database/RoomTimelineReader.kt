package dev.irohaco.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineReader
import dev.irohaco.core.contracts.TimelineSortKey
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTimelineReader(private val db: IrohacoDatabase) : TimelineReader {
    override suspend fun count(query: TimelineQuery): Int =
        db.timelineDao().count(SimpleSQLiteQuery("SELECT count(*) $FROM ${where(query)}", args(query)))

    override suspend fun page(query: TimelineQuery, offset: Int, limit: Int): List<TimelineItem> =
        db.timelineDao().page(
            SimpleSQLiteQuery(
                """SELECT a.asset_id AS assetId, a.media_kind AS mediaKind, s.content_uri AS contentUri, $TIME AS sortTimeMs
                   $FROM ${where(query)} ORDER BY ${orderBy(query)} LIMIT ? OFFSET ?""",
                arrayOf(*args(query), limit, offset),
            ),
        ).map { TimelineItem(it.assetId, MediaKind.valueOf(it.mediaKind), it.contentUri, it.sortTimeMs) }

    override suspend fun indexOf(query: TimelineQuery, assetId: String): Int? =
        db.timelineDao().indexOf(
            SimpleSQLiteQuery(
                """SELECT rn FROM (SELECT a.asset_id AS id, ROW_NUMBER() OVER (ORDER BY ${orderBy(query)}) - 1 AS rn
                   $FROM ${where(query)}) WHERE id = ?""",
                arrayOf(*args(query), assetId),
            ),
        )

    /**
     * Emits once on subscription as well: a sync that finishes after the first page loads but before
     * this flow is collected would otherwise leave the timeline showing stale rows.
     */
    override fun changes(): Flow<Unit> =
        db.invalidationTracker.createFlow("media_sources", "media_assets", emitInitialState = true).map { }

    private companion object {
        // shortcut: no index on the sort expressions yet; add them if M0-13 measures slow pages at 10k items.
        const val FROM = "FROM media_sources s JOIN media_assets a ON a.asset_id = s.asset_id"
        const val TIME = "COALESCE(s.observed_taken_at, s.observed_modified_at, a.first_seen_at)"

        fun where(query: TimelineQuery): String =
            "WHERE s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL" + if (query.kind != null) " AND a.media_kind = ?" else ""

        fun args(query: TimelineQuery): Array<Any> = listOfNotNull(query.kind?.name).toTypedArray()

        /** The asset id breaks ties in the same direction, so pages never overlap or skip. */
        fun orderBy(query: TimelineQuery): String {
            val key = when (query.sortKey) {
                TimelineSortKey.TAKEN -> TIME
                TimelineSortKey.NAME -> "s.display_name COLLATE NOCASE"
                TimelineSortKey.SIZE -> "s.file_size_bytes"
            }
            val direction = if (query.descending) "DESC" else "ASC"
            return "$key $direction, a.asset_id $direction"
        }
    }
}
