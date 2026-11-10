package dev.irohaco.feature.timeline

import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.insertSeparators
import androidx.paging.map
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.DateGrouping
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineLabel
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineSortKey
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.geometry.Rect
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

    /** An index into [DISTANCE_BANDS]; one past its end for files without a place. */
    data class Distance(val band: Int) : SortGroup
}

/** Upper edges of the distance bands in km, nearest first; the band after the last is everything farther. */
internal val DISTANCE_BANDS = intArrayOf(1, 10, 100, 1_000)

/** The band of [km]: 0 within 1 km, ..., DISTANCE_BANDS.size farther than the last edge, one more without a place. */
internal fun distanceBand(km: Double?): Int =
    if (km == null) DISTANCE_BANDS.size + 1 else DISTANCE_BANDS.indexOfFirst { km <= it }.takeIf { it >= 0 } ?: DISTANCE_BANDS.size

/** Lower edges of the size bands, largest first; the last band is everything smaller. */
internal val SIZE_BANDS = longArrayOf(100_000_000, 10_000_000, 1_000_000, 100_000, 0)

internal fun TimelineItem.sortGroup(key: TimelineSortKey): SortGroup? = when (key) {
    TimelineSortKey.TAKEN -> null
    TimelineSortKey.DISTANCE -> SortGroup.Distance(distanceBand(distanceKm))
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

    /** Counted once per source: any change to the library invalidates the source, and the next one counts again. */
    private var total: Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TimelineItem> {
        val key = params.key ?: 0
        val offset = if (params is LoadParams.Prepend) max(0, key - params.loadSize) else key
        val limit = if (params is LoadParams.Prepend) key - offset else params.loadSize
        val total = total ?: getPage.count(query).also { total = it }
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

/**
 * Where the headers fall among all cells, worked out from the whole order's labels before any page
 * loads. The grid then knows its size and every header's row from the start, so pages landing after
 * a fling no longer insert headers and shift what is on screen.
 */
internal class CellLayout(private val headerAt: IntArray, private val headers: List<TimelineCell>, val mediaCount: Int) {
    val cellCount: Int get() = mediaCount + headerAt.size

    fun isHeader(cell: Int): Boolean = headerAt.binarySearch(cell) >= 0

    fun header(cell: Int): TimelineCell? = headerAt.binarySearch(cell).takeIf { it >= 0 }?.let(headers::get)

    /** How many photos and videos come before [cell]: the media index of a media cell. */
    fun mediaBefore(cell: Int): Int = headerAt.binarySearch(cell).let { cell - if (it >= 0) it else -it - 1 }

    /** The grid's lines at [columns]: a header alone, or up to [columns] cells of one group. */
    fun rows(columns: Int): CellRows {
        val start = IntArray(cellCount)
        var rows = 0
        var cell = 0
        var h = 0
        while (cell < cellCount) {
            start[rows++] = cell
            cell = if (h < headerAt.size && headerAt[h] == cell) {
                h++
                cell + 1
            } else {
                min(cell + columns, if (h < headerAt.size) headerAt[h] else cellCount)
            }
        }
        return CellRows(start.copyOf(rows), cellCount)
    }
}

/**
 * Where each cell sits in a grid of [columns] across [width], with the line of [anchor] at [anchorY]: only the
 * lines between [top] and [bottom]. A header takes the whole width at [header] high; a photo is square.
 */
internal fun CellLayout.place(
    rows: CellRows,
    columns: Int,
    width: Float,
    spacing: Float,
    header: Float,
    anchor: Int,
    anchorY: Float,
    top: Float,
    bottom: Float,
): Map<Int, Rect> {
    val side = (width - (columns - 1) * spacing) / columns
    val placed = HashMap<Int, Rect>()
    fun height(row: Int) = if (isHeader(rows.first(row))) header else side
    fun put(row: Int, y: Float) {
        val first = rows.first(row)
        if (isHeader(first)) {
            placed[first] = Rect(0f, y, width, y + header)
        } else {
            for (cell in first until rows.end(row)) {
                val x = (cell - first) * (side + spacing)
                placed[cell] = Rect(x, y, x + side, y + side)
            }
        }
    }
    if (rows.count == 0) return placed
    val anchorRow = rows.rowOf(anchor)
    var row = anchorRow
    var y = anchorY
    while (row < rows.count && y < bottom) {
        put(row, y)
        y += height(row) + spacing
        row++
    }
    row = anchorRow
    y = anchorY
    while (row > 0 && y > top) {
        row--
        y -= height(row) + spacing
        put(row, y)
    }
    return placed
}

/** Where each line of the grid starts, in cells. */
internal class CellRows(private val start: IntArray, private val cellCount: Int) {
    val count: Int get() = start.size

    fun first(row: Int): Int = start[row]

    /** The cell after the last one of [row]. */
    fun end(row: Int): Int = if (row + 1 < start.size) start[row + 1] else cellCount

    fun rowOf(cell: Int): Int = start.binarySearch(cell).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
}

/** A header before each run of equal groups: a [LocalDate] for a date header, a [SortGroup] for any other. */
internal fun cellLayout(groups: List<Any>): CellLayout {
    val at = ArrayList<Int>()
    val headers = ArrayList<TimelineCell>()
    groups.forEachIndexed { i, group ->
        if (i == 0 || group != groups[i - 1]) {
            at += i + at.size
            headers += if (group is SortGroup) TimelineCell.Group(group) else TimelineCell.Header(group as LocalDate)
        }
    }
    return CellLayout(at.toIntArray(), headers, groups.size)
}

/** The group a label falls in, the same one [withDateHeaders] and [withSortHeaders] give its item. */
internal fun TimelineLabel.group(zone: ZoneId, grouping: DateGrouping): Any = when (this) {
    is TimelineLabel.Taken -> Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().groupStart(grouping)
    is TimelineLabel.Name -> SortGroup.Initial(firstChar.takeIf { it.isLetter() } ?: '#')
    is TimelineLabel.Size -> SortGroup.Size(SIZE_BANDS.indexOfFirst { bytes >= it })
    is TimelineLabel.Rating -> SortGroup.Stars(rating)
    is TimelineLabel.Distance -> SortGroup.Distance(distanceBand(km))
}

/**
 * Pages of cells, headers included, over a layout fixed for this source: placeholders stand for
 * headers too, so the grid's size and rows hold while pages load and jump.
 */
internal class TimelineCellPagingSource(
    private val getPage: GetTimelinePage,
    private val zone: ZoneId,
    private val grouping: DateGrouping,
    private val onLayout: (CellLayout) -> Unit = {},
    private val query: TimelineQuery = getPage.query.value,
) : PagingSource<Int, TimelineCell>() {
    override val jumpingSupported = true

    private var layout: CellLayout? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TimelineCell> {
        val layout = layout ?: cellLayout(getPage.labels(query).map { it.group(zone, grouping) }).also { layout = it; onLayout(it) }
        val total = layout.cellCount
        val key = (params.key ?: 0).coerceIn(0, total)
        val start = if (params is LoadParams.Prepend) max(0, key - params.loadSize) else key
        val end = if (params is LoadParams.Prepend) key else min(total, key + params.loadSize)
        val from = layout.mediaBefore(start)
        val items = getPage(from, layout.mediaBefore(end) - from, query)
        val cells = ArrayList<TimelineCell>(end - start)
        for (cell in start until end) {
            val header = layout.header(cell)
            // The library changed after the labels were read; a new source reads them again.
            val item = if (header == null) items.getOrNull(layout.mediaBefore(cell) - from) ?: return LoadResult.Invalid() else null
            cells += header ?: TimelineCell.Media(item!!, Instant.ofEpochMilli(item.sortTimeMs).atZone(zone).toLocalDate(), item.sortGroup(query.sortKey))
        }
        return LoadResult.Page(
            data = cells,
            prevKey = start.takeIf { it > 0 },
            nextKey = end.takeIf { it < total },
            itemsBefore = start,
            itemsAfter = total - end,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, TimelineCell>): Int? =
        state.anchorPosition?.let { max(0, it - state.config.initialLoadSize / 2) }
}
