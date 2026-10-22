package dev.irohaco.core.application

import dev.irohaco.core.contracts.LocatedAsset
import dev.irohaco.core.contracts.LocationStore
import dev.irohaco.core.contracts.MapSettings
import dev.irohaco.core.contracts.MediaDetailsReader
import dev.irohaco.core.contracts.PlaceNames
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaDetails
import dev.irohaco.core.domain.MediaDetailsResult
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationTest {
    private class FakeStore(ids: List<String>) : LocationStore {
        val pending = ids.toMutableList()
        val recorded = mutableMapOf<String, GeoPoint?>()
        val names = mutableMapOf<String, String>()
        override suspend fun unindexed(limit: Int) = pending.take(limit).map { TimelineItem(it, MediaKind.IMAGE, "content://$it", 0) }
        override suspend fun recordExif(assetId: String, point: GeoPoint?) {
            pending -= assetId
            recorded[assetId] = point
        }
        override suspend fun located() = recorded.mapNotNull { (id, p) -> p?.let { LocatedAsset(id, it) } }
        override suspend fun placeName(assetId: String) = names[assetId]
        override suspend fun setPlaceName(assetId: String, name: String) { names[assetId] = name }
        override fun changes() = emptyFlow<Unit>()
    }

    private fun details(location: Detail<GeoPoint>) = MediaDetailsResult.Read(
        MediaDetails(
            fileName = "a.jpg", folder = Detail.Missing, sizeBytes = Detail.Missing, mimeType = Detail.Missing,
            widthPx = Detail.Missing, heightPx = Detail.Missing, takenAt = Detail.Missing, durationMs = Detail.Missing,
            camera = Detail.Missing, lens = Detail.Missing, fNumber = Detail.Missing, exposureSeconds = Detail.Missing,
            iso = Detail.Missing, focalLengthMm = Detail.Missing, location = location, description = Detail.Missing, keywords = Detail.Missing,
        ),
    )

    private val tokyo = GeoPoint(35.68, 139.76)

    @Test
    fun `records each file's GPS, or that it has none, in batches until all are read`() = runTest {
        val store = FakeStore(listOf("a", "b", "c"))
        val reader = MediaDetailsReader { uri, _ -> details(if (uri == "content://a") Detail.Value(tokyo) else Detail.Missing) }
        IndexLocations(store, reader)(batch = 2)
        assertEquals(mapOf("a" to tokyo, "b" to null, "c" to null), store.recorded)
    }

    @Test
    fun `without the location permission it reads nothing and records nothing`() = runTest {
        val store = FakeStore(listOf("a", "b"))
        IndexLocations(store) { _, _ -> details(Detail.NoPermission) }()
        assertEquals(emptyMap(), store.recorded)
        assertEquals(listOf("a", "b"), store.pending) // read again once the permission is granted
    }

    private class FakeSettings(allowed: Boolean) : MapSettings {
        override val allowed = MutableStateFlow(allowed)
        override suspend fun setAllowed(allowed: Boolean) {
            this.allowed.value = allowed
        }
    }

    @Test
    fun `asks for place names only once the online map is allowed, and keeps them`() = runTest {
        var lookups = 0
        val places = PlaceNames { lookups++; "千代田区" }
        val settings = FakeSettings(false)
        val map = BrowseMap(FakeStore(emptyList()), settings, places)

        assertEquals(null, map.placeName("a", tokyo))
        assertEquals(0, lookups) // nothing leaves the device without consent

        map.allow(true)
        assertEquals("千代田区", map.placeName("a", tokyo))
        assertEquals("千代田区", map.placeName("a", tokyo))
        assertEquals(1, lookups) // the second time comes from the store
    }
}
