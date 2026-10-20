package dev.irohaco.feature.timeline

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource.LoadResult
import androidx.paging.testing.TestPager
import androidx.paging.testing.asSnapshot
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.DateGrouping
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineLabel
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineReader
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
        override suspend fun labelAt(query: TimelineQuery, index: Int) = items.getOrNull(index)?.let { TimelineLabel.Taken(it.sortTimeMs) }
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
    fun `a group ends on the first day of the next one`() {
        val start = LocalDate.of(2026, 12, 1)
        assertEquals(LocalDate.of(2027, 1, 1), groupEnd(start, DateGrouping.MONTH))
        assertEquals(LocalDate.of(2027, 1, 1), groupEnd(start.withDayOfYear(1), DateGrouping.YEAR))
        assertEquals(LocalDate.of(2026, 12, 2), groupEnd(start, DateGrouping.DAY))
    }
}
