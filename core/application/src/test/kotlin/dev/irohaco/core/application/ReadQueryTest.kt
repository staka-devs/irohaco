package dev.irohaco.core.application

import dev.irohaco.core.domain.MediaKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadQueryTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    // A Thursday in October.
    private val now = ZonedDateTime.of(2026, 10, 29, 12, 0, 0, 0, zone)

    private fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun read(query: String) = QueryReader.read(query, now)

    @Test
    fun `a Japanese sentence gives its dates, kind and the words left`() {
        assertEquals(ReadQuery("京都", ms(2025, 6, 1), ms(2025, 9, 1), MediaKind.VIDEO), read("去年の夏に京都で撮った動画"))
        assertEquals(ReadQuery("犬の散歩", ms(2026, 9, 1), ms(2026, 10, 1), MediaKind.IMAGE), read("先月の犬の散歩の写真"))
        assertEquals(ReadQuery("北海道", ms(2024, 12, 1), ms(2025, 3, 1)), read("2024年の冬の北海道"))
        assertEquals(ReadQuery("花火", ms(2026, 8, 1), ms(2026, 9, 1)), read("8月の花火"))
        assertEquals(ReadQuery("ケーキ", ms(2026, 10, 28), ms(2026, 10, 29)), read("昨日のケーキ"))
        assertEquals(ReadQuery("会議", ms(2026, 10, 19), ms(2026, 10, 26)), read("先週の会議"))
    }

    @Test
    fun `an English sentence gives its dates, kind and the words left`() {
        assertEquals(ReadQuery("Kyoto", ms(2026, 6, 1), ms(2026, 9, 1), MediaKind.VIDEO), read("videos from last summer in Kyoto"))
        assertEquals(ReadQuery("beach", ms(2025, 1, 1), ms(2026, 1, 1), MediaKind.IMAGE), read("photos of the beach last year"))
        assertEquals(ReadQuery("snow", ms(2026, 1, 1), ms(2026, 2, 1)), read("snow in January"))
    }

    @Test
    fun `a season not ended yet is last year's when said as last, and this year's when it began`() {
        val july = ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone)
        assertEquals(ms(2025, 6, 1), QueryReader.read("last summer", july).fromMs)
        assertEquals(ms(2026, 6, 1), QueryReader.read("夏の海", july).fromMs)
        assertEquals(ms(2025, 12, 1), QueryReader.read("冬の海", july).fromMs)
    }

    @Test
    fun `words that only look like conditions stay words`() {
        assertEquals(ReadQuery("秋田の祭り"), read("秋田の祭り"))
        assertEquals(ReadQuery("春巻き"), read("春巻き"))
        assertEquals(ReadQuery("夕日"), read("夕日"))
    }

    @Test
    fun `a sentence of conditions only is searched by its words`() {
        val read = read("去年の動画")
        assertEquals("去年の動画", read.rest)
        assertEquals(MediaKind.VIDEO, read.kind)
        assertEquals(ms(2025, 1, 1), read.fromMs)
    }
}
