package dev.irohaco.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.irohaco.core.contracts.MediaItems
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineLabel
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineReader
import dev.irohaco.core.contracts.TimelineSortKey
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTimelineReader(private val db: IrohacoDatabase) : TimelineReader, MediaItems {
    // A file in two places has two sources; the result keeps one row per asset.
    override suspend fun items(assetIds: List<String>): List<TimelineItem> = assetIds.distinct().chunked(MAX_ARGS).flatMap { ids ->
        db.timelineDao().page(
            SimpleSQLiteQuery(
                """SELECT a.asset_id AS assetId, a.media_kind AS mediaKind, s.content_uri AS contentUri, $TIME AS sortTimeMs, s.mime_type AS mimeType,
                          s.observed_duration_ms AS durationMs, s.display_name AS displayName, s.file_size_bytes AS sizeBytes, 0 AS rating
                   $FROM WHERE s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL AND a.asset_id IN (${ids.joinToString { "?" }})""",
                ids.toTypedArray(),
            ),
        ).map { TimelineItem(it.assetId, MediaKind.valueOf(it.mediaKind), it.contentUri, it.sortTimeMs, it.mimeType, it.durationMs, it.displayName, it.sizeBytes) }
    }.distinctBy { it.assetId }

    override suspend fun count(query: TimelineQuery): Int =
        db.timelineDao().count(SimpleSQLiteQuery("SELECT count(*) ${from(query)} ${where(query)}", arrayOf(*fromArgs(query), *args(query))))

    override suspend fun page(query: TimelineQuery, offset: Int, limit: Int): List<TimelineItem> =
        db.timelineDao().page(
            SimpleSQLiteQuery(
                """SELECT a.asset_id AS assetId, a.media_kind AS mediaKind, s.content_uri AS contentUri, $TIME AS sortTimeMs, s.mime_type AS mimeType,
                          s.observed_duration_ms AS durationMs, s.display_name AS displayName, s.file_size_bytes AS sizeBytes,
                          COALESCE(r.rating, 0) AS rating
                   ${from(query)} ${where(query)} ORDER BY ${orderBy(query)} LIMIT ? OFFSET ?""",
                arrayOf(*fromArgs(query), *args(query), limit, offset),
            ),
        ).map { TimelineItem(it.assetId, MediaKind.valueOf(it.mediaKind), it.contentUri, it.sortTimeMs, it.mimeType, it.durationMs, it.displayName, it.sizeBytes, it.rating ?: 0) }

    override suspend fun indexOf(query: TimelineQuery, assetId: String): Int? =
        db.timelineDao().indexOf(
            SimpleSQLiteQuery(
                """SELECT rn FROM (SELECT a.asset_id AS id, ROW_NUMBER() OVER (ORDER BY ${orderBy(query)}) - 1 AS rn
                   ${from(query)} ${where(query)}) WHERE id = ?""",
                arrayOf(*fromArgs(query), *args(query), assetId),
            ),
        )

    override suspend fun between(query: TimelineQuery, fromMs: Long, untilMs: Long): List<TimelineItem> =
        db.timelineDao().page(
            SimpleSQLiteQuery(
                """SELECT a.asset_id AS assetId, a.media_kind AS mediaKind, s.content_uri AS contentUri, $TIME AS sortTimeMs, s.mime_type AS mimeType,
                          s.observed_duration_ms AS durationMs, s.display_name AS displayName, s.file_size_bytes AS sizeBytes,
                          COALESCE(r.rating, 0) AS rating
                   ${from(query)} ${where(query)} AND $TIME >= ? AND $TIME < ? ORDER BY ${orderBy(query)}""",
                arrayOf(*fromArgs(query), *args(query), fromMs, untilMs),
            ),
        ).map { TimelineItem(it.assetId, MediaKind.valueOf(it.mediaKind), it.contentUri, it.sortTimeMs, it.mimeType, it.durationMs, it.displayName, it.sizeBytes, it.rating ?: 0) }

    override suspend fun labels(query: TimelineQuery): List<TimelineLabel> =
        db.timelineDao().labelRows(
            SimpleSQLiteQuery(
                """SELECT $TIME AS takenMs, s.display_name AS displayName, s.file_size_bytes AS sizeBytes, COALESCE(r.rating, 0) AS rating
                   ${from(query)} ${where(query)} ORDER BY ${orderBy(query)}""",
                arrayOf(*fromArgs(query), *args(query)),
            ),
        ).map { row -> label(query, row) }

    private fun label(query: TimelineQuery, row: LabelRow): TimelineLabel =
        when (query.sortKey) {
            TimelineSortKey.TAKEN -> TimelineLabel.Taken(row.takenMs)
            TimelineSortKey.NAME -> TimelineLabel.Name(row.displayName.firstOrNull()?.uppercaseChar() ?: '#')
            TimelineSortKey.SIZE -> TimelineLabel.Size(row.sizeBytes)
            TimelineSortKey.RATING -> TimelineLabel.Rating(row.rating)
        }

    /**
     * Emits once on subscription as well: a sync that finishes after the first page loads but before
     * this flow is collected would otherwise leave the timeline showing stale rows.
     */
    override fun changes(): Flow<Unit> =
        db.invalidationTracker.createFlow("media_sources", "media_assets", "media_ratings", "media_tags", "album_items", emitInitialState = true).map { }

    private companion object {
        // shortcut: no index on the sort expressions yet; add them if M0-13 measures slow pages at 10k items.
        const val FROM = "FROM media_sources s JOIN media_assets a ON a.asset_id = s.asset_id " +
            "LEFT JOIN media_ratings r ON r.asset_id = a.asset_id"
        const val MAX_ARGS = 500
        const val TIME = "COALESCE(s.observed_taken_at, s.observed_modified_at, a.first_seen_at)"

        // The volume root has a null relative path in MediaStore; FolderRef calls it "".
        const val PATH = "COALESCE(s.relative_path, '')"

        /** An album joins its items for their order; the album id is the only placeholder here. */
        fun from(query: TimelineQuery): String =
            FROM + if (query.albumId != null) " JOIN album_items ai ON ai.asset_id = a.asset_id AND ai.album_id = ?" else ""

        fun fromArgs(query: TimelineQuery): Array<Any> = listOfNotNull(query.albumId).toTypedArray()

        fun where(query: TimelineQuery): String = buildString {
            append("WHERE s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL")
            if (query.kind != null) append(" AND a.media_kind = ?")
            if (query.favoritesOnly) append(" AND r.is_favorite = 1")
            if (query.tagId != null) append(" AND EXISTS (SELECT 1 FROM media_tags mt WHERE mt.asset_id = a.asset_id AND mt.tag_id = ?)")
            if (query.folder != null) append(" AND s.volume_id = ? AND $PATH = ?")
            repeat(query.excludedFolders.size) { append(" AND NOT (s.volume_id = ? AND $PATH = ?)") }
            if (query.area != null) {
                append(
                    " AND EXISTS (SELECT 1 FROM location_evidence le WHERE le.asset_id = a.asset_id AND le.evidence_source = 'EXIF'" +
                        " AND le.evidence_status = 'CONFIRMED' AND le.latitude BETWEEN ? AND ? AND le.longitude BETWEEN ? AND ?)",
                )
            }
        }

        /** In the order of the placeholders in [where]. */
        fun args(query: TimelineQuery): Array<Any> = buildList {
            query.kind?.let { add(it.name) }
            query.tagId?.let { add(it) }
            query.folder?.let { add(it.volumeId); add(it.relativePath) }
            query.excludedFolders.forEach { add(it.volumeId); add(it.relativePath) }
            query.area?.let { add(it.south); add(it.north); add(it.west); add(it.east) }
        }.toTypedArray()

        /** The asset id breaks ties in the same direction, so pages never overlap or skip. */
        fun orderBy(query: TimelineQuery): String {
            if (query.albumId != null) return "ai.sort_order ASC, a.asset_id ASC"
            val key = when (query.sortKey) {
                TimelineSortKey.TAKEN -> TIME
                TimelineSortKey.NAME -> "s.display_name COLLATE NOCASE"
                TimelineSortKey.SIZE -> "s.file_size_bytes"
                TimelineSortKey.RATING -> "COALESCE(r.rating, 0)"
            }
            val direction = if (query.descending) "DESC" else "ASC"
            return "$key $direction, a.asset_id $direction"
        }
    }
}
