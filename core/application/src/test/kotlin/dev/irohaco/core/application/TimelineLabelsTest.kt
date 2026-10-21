package dev.irohaco.core.application

import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineLabel
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineReader
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fast scrollbar's bubble reads its labels from memory while the finger moves (M1-35). */
class TimelineLabelsTest {
    private var loads = 0
    private var now = 0L
    private val reader = object : TimelineReader {
        override suspend fun count(query: TimelineQuery) = 3
        override suspend fun page(query: TimelineQuery, offset: Int, limit: Int) = emptyList<TimelineItem>()
        override suspend fun indexOf(query: TimelineQuery, assetId: String): Int? = null
        override suspend fun between(query: TimelineQuery, fromMs: Long, untilMs: Long) = emptyList<TimelineItem>()
        override suspend fun labels(query: TimelineQuery): List<TimelineLabel> {
            loads++
            return listOf(30L, 20L, 10L).map { TimelineLabel.Taken(it) }
        }
        override fun changes() = emptyFlow<Unit>()
    }
    private val page = GetTimelinePage(reader) { now }

    @Test
    fun `reads the labels once for many positions`() = runTest {
        assertEquals(TimelineLabel.Taken(20), page.labelAt(1))
        assertEquals(TimelineLabel.Taken(10), page.labelAt(2))
        assertEquals(null, page.labelAt(3))
        assertEquals(1, loads)
    }

    @Test
    fun `reads them again for another order or after a while`() = runTest {
        page.labelAt(0)
        page.labelAt(0, TimelineQuery(descending = false))
        assertEquals(2, loads)
        now += 31_000
        page.labelAt(0, TimelineQuery(descending = false))
        assertEquals(3, loads)
    }
}
