package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** Raw queries, because the ORDER BY changes with the chosen sort; [RoomTimelineReader] builds them. */
@Dao
interface TimelineDao {
    @RawQuery
    suspend fun page(query: SupportSQLiteQuery): List<TimelineRow>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int

    @RawQuery
    suspend fun indexOf(query: SupportSQLiteQuery): Int?

    @RawQuery
    suspend fun labelRow(query: SupportSQLiteQuery): LabelRow?
}

data class TimelineRow(val assetId: String, val mediaKind: String, val contentUri: String, val sortTimeMs: Long)

/** Every column the fast scrollbar's bubble might need, whichever sort is active ([RoomTimelineReader] picks one). */
data class LabelRow(val takenMs: Long, val displayName: String, val sizeBytes: Long, val rating: Int)
