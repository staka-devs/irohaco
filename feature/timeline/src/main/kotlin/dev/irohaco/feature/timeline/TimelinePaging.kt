package dev.irohaco.feature.timeline

import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.insertSeparators
import androidx.paging.map
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.DateGrouping
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineSortKey
import kotlin.math.max
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface TimelineCell {
    data class Header(val date: LocalDate) : TimelineCell

    /** The header of a group when the grid is sorted by name, size or rating (M1-36). */
    data class Group(val group: SortGroup) : TimelineCell

    data class Media(val item: TimelineItem, val date: LocalDate, val group: SortGroup? = null) : TimelineCell
}

internal val TimelineCell.isHeader: Boolean get() = this is TimelineCell.Header || this is TimelineCell.Group

/** What a non-date sort groups by: a name's first letter, a size band or a star count (M1-36). */
sealed interface SortGroup {
    /** '#' for names that start with a digit or a symbol. */
    data class Initial(val letter: Char) : SortGroup

    /** An index into [SIZE_BANDS]: 0 is the largest band. */
    data class Size(val band: Int) : SortGroup

    /** 0 is no rating. */
    data class Stars(val count: Int) : SortGroup
}

/** Lower edges of the size bands, largest first; the last band is everything smaller. */
internal val SIZE_BANDS = longArrayOf(100_000_000, 10_000_000, 1_000_000, 100_000, 0)

internal fun TimelineItem.sortGroup(key: TimelineSortKey): SortGroup? = when (key) {
    TimelineSortKey.TAKEN -> null
    TimelineSortKey.NAME -> SortGroup.Initial(displayName?.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#')
    TimelineSortKey.SIZE -> SortGroup.Size(SIZE_BANDS.indexOfFirst { (sizeBytes ?: 0) >= it })
    TimelineSortKey.RATING -> SortGroup.Stars(rating)
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

/**
 * Puts a header before the first item of each local day, month or year (GAL-012). A header carries
 * the first day of its group; the cells keep their own day for their descriptions.
 */
internal fun PagingData<TimelineItem>.withDateHeaders(zone: ZoneId, grouping: DateGrouping = DateGrouping.DAY): PagingData<TimelineCell> =
    mediaCells(zone)
        .insertSeparators { before: TimelineCell.Media?, after: TimelineCell.Media? ->
            val group = after?.date?.groupStart(grouping)
            if (group != null && group != before?.date?.groupStart(grouping)) TimelineCell.Header(group) else null
        }

internal fun LocalDate.groupStart(grouping: DateGrouping): LocalDate = when (grouping) {
    DateGrouping.YEAR -> withDayOfYear(1)
    DateGrouping.MONTH -> withDayOfMonth(1)
    DateGrouping.DAY -> this
}

/** The first day after the group that starts on [start]. */
internal fun groupEnd(start: LocalDate, grouping: DateGrouping): LocalDate = when (grouping) {
    DateGrouping.YEAR -> start.plusYears(1)
    DateGrouping.MONTH -> start.plusMonths(1)
    DateGrouping.DAY -> start.plusDays(1)
}

/** Sorted by name, size or rating: a header before each group of that key, not of dates (M1-36). */
internal fun PagingData<TimelineItem>.withSortHeaders(zone: ZoneId, key: TimelineSortKey): PagingData<TimelineCell> =
    mediaCells(zone, key)
        .insertSeparators { before: TimelineCell.Media?, after: TimelineCell.Media? ->
            val group = after?.group
            if (group != null && group != before?.group) TimelineCell.Group(group) else null
        }

private fun PagingData<TimelineItem>.mediaCells(zone: ZoneId, key: TimelineSortKey = TimelineSortKey.TAKEN): PagingData<TimelineCell.Media> =
    map { TimelineCell.Media(it, Instant.ofEpochMilli(it.sortTimeMs).atZone(zone).toLocalDate(), it.sortGroup(key)) }
