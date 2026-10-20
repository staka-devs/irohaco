package dev.irohaco.feature.timeline

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource.LoadResult
import androidx.paging.testing.TestPager
import androidx.paging.testing.asSnapshot
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.TimelineItem
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
        override suspend fun count() = items.size
        override suspend fun page(offset: Int, limit: Int) = items.drop(offset).take(limit)
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
}
