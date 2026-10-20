package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface TimelineDao {
    // shortcut: no index on the sort expression yet; add one if M0-13 measures slow pages at 10k items.
    @Query(
        """SELECT a.asset_id AS assetId, a.media_kind AS mediaKind, s.content_uri AS contentUri,
                  COALESCE(s.observed_taken_at, s.observed_modified_at, a.first_seen_at) AS sortTimeMs
           FROM media_sources s JOIN media_assets a ON a.asset_id = s.asset_id
           WHERE s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL
           ORDER BY sortTimeMs DESC, a.asset_id DESC
           LIMIT :limit OFFSET :offset""",
    )
    suspend fun page(offset: Int, limit: Int): List<TimelineRow>

    @Query(
        """SELECT count(*) FROM media_sources s JOIN media_assets a ON a.asset_id = s.asset_id
           WHERE s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL""",
    )
    suspend fun count(): Int

    /** Items that come before the asset in the page order, or null when it is not available. */
    @Query(
        """WITH t AS (
             SELECT a.asset_id AS id, COALESCE(s.observed_taken_at, s.observed_modified_at, a.first_seen_at) AS ts
             FROM media_sources s JOIN media_assets a ON a.asset_id = s.asset_id
             WHERE s.access_state = 'AVAILABLE' AND a.tombstoned_at IS NULL)
           SELECT (SELECT count(*) FROM t WHERE t.ts > me.ts OR (t.ts = me.ts AND t.id > me.id))
           FROM t AS me WHERE me.id = :assetId""",
    )
    suspend fun indexOf(assetId: String): Int?
}

data class TimelineRow(val assetId: String, val mediaKind: String, val contentUri: String, val sortTimeMs: Long)
