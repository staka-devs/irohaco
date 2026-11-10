package dev.irohaco.feature.timeline

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource.LoadResult
import androidx.paging.testing.TestPager
import androidx.paging.testing.asSnapshot
import androidx.compose.ui.geometry.Rect
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.DateGrouping
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineLabel
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineReader
import dev.irohaco.core.contracts.TimelineSortKey
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelinePagingTest {
    private val day = 86_400_000L
    private val items = (0 until 10).map { TimelineItem("a$it", MediaKind.IMAGE, "content://$it", sortTimeMs = (9 - it / 3) * day) }
    private val reader = object : TimelineReader {
        override suspend fun count(query: TimelineQuery) = items.size
        override suspend fun page(query: TimelineQuery, offset: Int, limit: Int) = items.drop(offset).take(limit)
        override suspend fun indexOf(query: TimelineQuery, assetId: String) = items.indexOfFirst { it.assetId == assetId }.takeIf { it >= 0 }
        override suspend fun between(query: TimelineQuery, fromMs: Long, untilMs: Long) = items.filter { it.sortTimeMs in fromMs until untilMs }
        override suspend fun labels(query: TimelineQuery) = items.map { TimelineLabel.Taken(it.sortTimeMs) }
        override fun changes() = emptyFlow<Unit>()
    }
    private val config = PagingConfig(pageSize = 4, initialLoadSize = 4, enablePlaceholders = true)

    @Test
    fun `pages carry placeholders for the rest of the timeline`() = runTest {
        val pager = TestPager(config, TimelinePagingSource(GetTimelinePage(reader)))
        val first = pager.refresh() as LoadResult.Page
        assertEquals(listOf("a0", "a1", "a2", "a3"), first.data.map { it.assetId })
        assertNull(first.prevKey)
        assertEquals(0, first.itemsBefore)
        assertEquals(6, first.itemsAfter)
        val last = pager.append() as LoadResult.Page
        assertEquals(4, last.itemsBefore)
    }

    @Test
    fun `a refresh in the middle can load the items before it`() = runTest {
        val pager = TestPager(config, TimelinePagingSource(GetTimelinePage(reader)))
        val middle = pager.refresh(initialKey = 6) as LoadResult.Page
        assertEquals(listOf("a6", "a7", "a8", "a9"), middle.data.map { it.assetId })
        assertNull(middle.nextKey)
        val before = pager.prepend() as LoadResult.Page
        assertEquals(listOf("a2", "a3", "a4", "a5"), before.data.map { it.assetId })
        assertEquals(2, before.prevKey)
    }

    @Test
    fun `a date header comes before the first item of each day`() = runTest {
        val cells = flowOf(PagingData.from(items.take(5)).withDateHeaders(ZoneOffset.UTC)).asSnapshot()
        val day9 = LocalDate.ofEpochDay(9)
        val day8 = LocalDate.ofEpochDay(8)
        assertEquals(
            listOf("h$day9", "a0", "a1", "a2", "h$day8", "a3", "a4"),
            cells.map { if (it is TimelineCell.Header) "h${it.date}" else (it as TimelineCell.Media).item.assetId },
        )
    }

    @Test
    fun `month and year headers carry the first day of the group and items keep their own day`() = runTest {
        fun at(date: LocalDate, id: String) = TimelineItem(id, MediaKind.IMAGE, "content://$id", date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        val shots = listOf(at(LocalDate.of(2026, 10, 20), "a"), at(LocalDate.of(2026, 10, 2), "b"), at(LocalDate.of(2026, 9, 30), "c"), at(LocalDate.of(2025, 12, 1), "d"))
        val months = flowOf(PagingData.from(shots).withDateHeaders(ZoneOffset.UTC, DateGrouping.MONTH)).asSnapshot()
        assertEquals(
            listOf("h2026-10-01", "a", "b", "h2026-09-01", "c", "h2025-12-01", "d"),
            months.map { if (it is TimelineCell.Header) "h${it.date}" else (it as TimelineCell.Media).item.assetId },
        )
        assertEquals(LocalDate.of(2026, 10, 20), (months[1] as TimelineCell.Media).date)
        val years = flowOf(PagingData.from(shots).withDateHeaders(ZoneOffset.UTC, DateGrouping.YEAR)).asSnapshot()
        assertEquals(
            listOf("h2026-01-01", "a", "b", "c", "h2025-01-01", "d"),
            years.map { if (it is TimelineCell.Header) "h${it.date}" else (it as TimelineCell.Media).item.assetId },
        )
    }

    @Test
    fun `sorted by name, a header comes before each first letter instead of a date`() = runTest {
        fun named(name: String) = TimelineItem(name, MediaKind.IMAGE, "content://$name", sortTimeMs = 0, displayName = name)
        val cells = flowOf(PagingData.from(listOf(named("1.jpg"), named("apple.jpg"), named("Avocado.jpg"), named("beach.jpg")))
            .withSortHeaders(ZoneOffset.UTC, TimelineSortKey.NAME)).asSnapshot()
        assertEquals(
            listOf("g#", "1.jpg", "gA", "apple.jpg", "Avocado.jpg", "gB", "beach.jpg"),
            cells.map {
                when (it) {
                    is TimelineCell.Group -> "g${(it.group as SortGroup.Initial).letter}"
                    is TimelineCell.Media -> it.item.assetId
                    is TimelineCell.Header -> "date"
                }
            },
        )
    }

    @Test
    fun `size and rating groups`() {
        fun sized(bytes: Long) = TimelineItem("s", MediaKind.IMAGE, "content://s", 0, sizeBytes = bytes)
        assertEquals(SortGroup.Size(0), sized(250_000_000).sortGroup(TimelineSortKey.SIZE))
        assertEquals(SortGroup.Size(2), sized(3_000_000).sortGroup(TimelineSortKey.SIZE))
        assertEquals(SortGroup.Size(4), sized(5_000).sortGroup(TimelineSortKey.SIZE))
        assertEquals(SortGroup.Stars(0), TimelineItem("r", MediaKind.IMAGE, "content://r", 0).sortGroup(TimelineSortKey.RATING))
        assertNull(sized(1).sortGroup(TimelineSortKey.TAKEN))
    }

    @Test
    fun `a group ends on the first day of the next one`() {
        val start = LocalDate.of(2026, 12, 1)
        assertEquals(LocalDate.of(2027, 1, 1), groupEnd(start, DateGrouping.MONTH))
        assertEquals(LocalDate.of(2027, 1, 1), groupEnd(start.withDayOfYear(1), DateGrouping.YEAR))
        assertEquals(LocalDate.of(2026, 12, 2), groupEnd(start, DateGrouping.DAY))
    }

    private fun TimelineCell.name() = when (this) {
        is TimelineCell.Header -> "H$date"
        is TimelineCell.Group -> "G$group"
        is TimelineCell.Media -> item.assetId
    }

    @Test
    fun `the layout places every header before any page loads`() = runTest {
        var layout: CellLayout? = null
        val pager = TestPager(config, TimelineCellPagingSource(GetTimelinePage(reader), ZoneOffset.UTC, DateGrouping.DAY, { layout = it }))
        val first = pager.refresh() as LoadResult.Page
        assertEquals(listOf("H1970-01-10", "a0", "a1", "a2"), first.data.map { it.name() })
        assertEquals(10, first.itemsAfter) // 10 items and 4 day headers
        assertEquals(listOf(0, 4, 8, 12), (0 until 14).filter { layout!!.isHeader(it) })
        assertEquals(9, layout!!.mediaBefore(13))
        val next = pager.append() as LoadResult.Page
        assertEquals(listOf("H1970-01-09", "a3", "a4", "a5"), next.data.map { it.name() })
    }

    @Test
    fun `a jump loads the cells of that position, headers included`() = runTest {
        val pager = TestPager(config, TimelineCellPagingSource(GetTimelinePage(reader), ZoneOffset.UTC, DateGrouping.DAY))
        val end = pager.refresh(initialKey = 10) as LoadResult.Page
        assertEquals(listOf("a7", "a8", "H1970-01-07", "a9"), end.data.map { it.name() })
        assertEquals(10, end.itemsBefore)
        assertEquals(0, end.itemsAfter)
        val before = pager.prepend() as LoadResult.Page
        assertEquals(listOf("a4", "a5", "H1970-01-08", "a6"), before.data.map { it.name() })
    }

    @Test
    fun `the layout matches the headers inserted between loaded items`() = runTest {
        val inserted = flowOf(PagingData.from(items).withDateHeaders(ZoneOffset.UTC, DateGrouping.MONTH)).asSnapshot().map { it.name() }
        val pager = TestPager(PagingConfig(pageSize = 20, initialLoadSize = 20), TimelineCellPagingSource(GetTimelinePage(reader), ZoneOffset.UTC, DateGrouping.MONTH))
        assertEquals(inserted, (pager.refresh() as LoadResult.Page).data.map { it.name() })
    }

    @Test
    fun rowsBreakAtHeadersAndColumns() {
        // Groups of 3 and 5: H a a a | H a a a a a, at 2 columns.
        val rows = cellLayout(listOf(1, 1, 1, 2, 2, 2, 2, 2).map { LocalDate.ofEpochDay(it.toLong()) }).rows(2)
        assertEquals(listOf(0, 1, 3, 4, 5, 7, 9), (0 until rows.count).map(rows::first))
        assertEquals(10, rows.end(rows.count - 1))
        assertEquals(1, rows.rowOf(2))
        assertEquals(4, rows.rowOf(5))
        assertEquals(6, rows.rowOf(9))
    }

    @Test
    fun pinchProgressRunsFromNoneToTheNextCount() {
        assertEquals(0f, pinchProgress(1f, 1.4f))
        assertEquals(1f, pinchProgress(1.4f, 1.4f), 1e-4f)
        assertEquals(1f, pinchProgress(2f, 1.4f))
        assertEquals(0f, pinchProgress(0.9f, 1.4f))
        assertEquals(0.5f, pinchProgress(0.8366f, 0.7f), 1e-3f)
    }

    @Test
    fun placeLaysCellsOutAroundTheAnchor() {
        // H a a a | H a a a a a at 2 columns, 10 wide: photos are 5 square, headers 2 high.
        val layout = cellLayout(listOf(1, 1, 1, 2, 2, 2, 2, 2).map { LocalDate.ofEpochDay(it.toLong()) })
        val placed = layout.place(layout.rows(2), 2, width = 10f, spacing = 0f, header = 2f, anchor = 5, anchorY = 10f, top = 0f, bottom = 25f)
        assertEquals(Rect(0f, 10f, 5f, 15f), placed[5])
        assertEquals(Rect(5f, 10f, 10f, 15f), placed[6])
        assertEquals(Rect(0f, 8f, 10f, 10f), placed[4])
        assertEquals(Rect(0f, 20f, 5f, 25f), placed[9])
        assertEquals(Rect(0f, -2f, 5f, 3f), placed[1])
        assertNull(placed[0])
    }
}
