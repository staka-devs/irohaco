package dev.irohaco.feature.timeline

import org.junit.Test
import kotlin.test.assertEquals

/** Typing a place finds prefectures and towns alike, by any part of their names. */
class PlacePickerTest {
    private val counts = mapOf("徳島市, 徳島県" to 120, "三好市, 徳島県" to 30, "大阪市, 大阪府" to 80)

    @Test
    fun `a part finds the prefecture and its towns, most files first`() {
        assertEquals(listOf("徳島県" to 150, "徳島市, 徳島県" to 120, "三好市, 徳島県" to 30), matchPlaces(counts, "徳島"))
    }

    @Test
    fun `a town is found by its own name`() {
        assertEquals(listOf("三好市, 徳島県" to 30), matchPlaces(counts, " 三好 "))
    }

    @Test
    fun `nothing typed finds nothing`() {
        assertEquals(emptyList(), matchPlaces(counts, " "))
    }
}
