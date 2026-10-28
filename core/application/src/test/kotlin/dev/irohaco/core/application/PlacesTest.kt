package dev.irohaco.core.application

import dev.irohaco.core.contracts.InferredPlace
import dev.irohaco.core.contracts.PlaceEvidenceStore
import dev.irohaco.core.contracts.PlaceMatch
import dev.irohaco.core.contracts.TimedPoint
import dev.irohaco.core.domain.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Places are named only with consent, guessed from photos close in time, and found by the names a query holds (M2-13). */
class PlacesTest {
    private val tokushima = GeoPoint(34.07, 134.55)
    private val areas = mutableListOf(tokushima, GeoPoint(0.0, 0.0))
    private val named = mutableMapOf<GeoPoint, String>()
    private var inferred = emptyList<InferredPlace>()

    private val store = object : PlaceEvidenceStore {
        override suspend fun unnamedAreas(limit: Int) = areas.filter { it !in named }.take(limit)
        override suspend fun nameArea(area: GeoPoint, name: String) {
            named[area] = name
        }
        override suspend fun locatedTimes() = listOf(
            TimedPoint("gps1", 1_000_000, tokushima, "徳島市, 徳島県"),
            TimedPoint("gps2", 10_000_000, GeoPoint(35.0, 135.7), "京都市, 京都府"),
        )
        override suspend fun unlocatedTimes() = listOf("near" to 1_600_000L, "far" to 5_500_000L, "late" to 10_100_000L)
        override suspend fun replaceInferred(places: List<InferredPlace>) {
            inferred = places
        }
        override suspend fun placeNames(includeInferred: Boolean) = listOf("徳島市, 徳島県", "京都市, 京都府") + if (includeInferred) listOf("Tokushima, Tokushima") else emptyList()
        override suspend fun filesAt(names: Collection<String>, includeInferred: Boolean, limit: Int) = names.map { PlaceMatch(it, it, inferred = it.startsWith("T")) }
    }

    @Test
    fun `places are named only once the user allowed it, and a place without a name is not asked again`() = runTest {
        val allowed = MutableStateFlow(false)
        val namePlaces = NamePlaces(store, { if (it == tokushima) "徳島市, 徳島県" else null }, allowed)
        namePlaces()
        assertEquals(emptyMap(), named)
        allowed.value = true
        namePlaces()
        assertEquals(mapOf(tokushima to "徳島市, 徳島県", GeoPoint(0.0, 0.0) to ""), named)
    }

    @Test
    fun `a photo without GPS takes the place of the located photo closest in time, within an hour`() = runTest {
        InferPlaces(store)()
        assertEquals(listOf("near" to "gps1", "late" to "gps2"), inferred.map { it.assetId to it.supportAssetId })
        assertEquals("徳島市, 徳島県", inferred.first().placeName)
        assertEquals(true, inferred.all { it.confidence in 0.1..0.9 })
        assertEquals(true, inferred[1].confidence > inferred[0].confidence)
    }

    @Test
    fun `a query names a place by one of its parts`() = runTest {
        assertEquals(true, SearchPlaces.mentions("徳島で撮った夕日", "徳島市, 徳島県"))
        assertEquals(true, SearchPlaces.mentions("京都の紅葉", "京都市, 京都府"))
        assertEquals(true, SearchPlaces.mentions("ｔｏｋｕｓｈｉｍａ ramen", "Tokushima, Tokushima"))
        assertEquals(false, SearchPlaces.mentions("大阪の夜景", "京都市, 京都府"))
        assertEquals(listOf("徳島市, 徳島県"), SearchPlaces(store)("徳島", includeInferred = false).map { it.placeName })
        assertEquals(2, SearchPlaces(store)("徳島 tokushima", includeInferred = true).size)
    }
}
