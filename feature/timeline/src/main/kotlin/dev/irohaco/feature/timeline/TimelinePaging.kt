package dev.irohaco.feature.timeline

import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.insertSeparators
import androidx.paging.map
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineQuery
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

sealed interface TimelineCell {
    data class Header(val date: LocalDate) : TimelineCell

    data class Media(val item: TimelineItem, val date: LocalDate) : TimelineCell
}

/**
 * Offset pages with placeholders, so the grid keeps its size while pages load and a refresh after
 * a catalog change starts around the visible position instead of the top.
 */
internal class TimelinePagingSource(
    private val getPage: GetTimelinePage,
    /** Fixed for this source, so pages never mix orders; a new choice invalidates the source. */
    private val query: TimelineQuery = getPage.query.value,
) : PagingSource<Int, TimelineItem>() {
    override val jumpingSupported = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TimelineItem> {
        val key = params.key ?: 0
        val offset = if (params is LoadParams.Prepend) max(0, key - params.loadSize) else key
        val limit = if (params is LoadParams.Prepend) key - offset else params.loadSize
        val total = getPage.count(query)
        val data = getPage(offset, limit, query)
        val end = offset + data.size
        return LoadResult.Page(
            data = data,
            prevKey = offset.takeIf { it > 0 },
            nextKey = end.takeIf { it < total && data.isNotEmpty() },
            itemsBefore = offset,
            itemsAfter = max(0, total - end),
        )
    }

    override fun getRefreshKey(state: PagingState<Int, TimelineItem>): Int? =
        state.anchorPosition?.let { max(0, it - state.config.initialLoadSize / 2) }
}

/** Puts a date header before the first item of each local day. */
internal fun PagingData<TimelineItem>.withDateHeaders(zone: ZoneId): PagingData<TimelineCell> =
    mediaCells(zone)
        .insertSeparators { before: TimelineCell.Media?, after: TimelineCell.Media? ->
            if (after != null && after.date != before?.date) TimelineCell.Header(after.date) else null
        }

/** Dates mean nothing as headers when the grid is sorted by name or size; the cells keep them for their labels. */
internal fun PagingData<TimelineItem>.withoutHeaders(zone: ZoneId): PagingData<TimelineCell> = mediaCells(zone).map { it }

private fun PagingData<TimelineItem>.mediaCells(zone: ZoneId): PagingData<TimelineCell.Media> =
    map { TimelineCell.Media(it, Instant.ofEpochMilli(it.sortTimeMs).atZone(zone).toLocalDate()) }
