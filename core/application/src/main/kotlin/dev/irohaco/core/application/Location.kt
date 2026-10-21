package dev.irohaco.core.application

import dev.irohaco.core.contracts.LocationStore
import dev.irohaco.core.contracts.MapProvider
import dev.irohaco.core.contracts.MapSettings
import dev.irohaco.core.contracts.MediaDetailsReader
import dev.irohaco.core.contracts.PlaceNames
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaDetailsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield

/**
 * Reads the GPS of each photo and video a few at a time, apart from the catalog sync (PRD GAL-017).
 * Cancelling the call pauses it; calling again resumes where it stopped, as the store remembers what
 * was read. Without the location permission it stops and reads nothing: the OS would hide the GPS.
 */
class IndexLocations(private val store: LocationStore, private val reader: MediaDetailsReader) {
    suspend operator fun invoke(batch: Int = 20) {
        while (true) {
            val items = store.unindexed(batch)
            if (items.isEmpty()) return
            for (item in items) {
                val location = (reader.read(item.contentUri, item.kind) as? MediaDetailsResult.Read)?.details?.location
                when (location) {
                    Detail.NoPermission -> return
                    is Detail.Value -> store.recordExif(item.assetId, location.value)
                    // No GPS, an unreadable one or a vanished file: remembered, so it is not read on every resume.
                    else -> store.recordExif(item.assetId, null)
                }
                yield()
            }
        }
    }
}

/** The map and the place names, which go outside only once the user allowed it (ADR-0011). */
class BrowseMap(private val store: LocationStore, private val settings: MapSettings, private val places: PlaceNames) {
    suspend fun located() = store.located()

    fun changes() = store.changes()

    val provider: Flow<MapProvider> = settings.provider

    /** Whether the active provider may receive the map's area and place lookups. */
    val allowed: Flow<Boolean> = combine(settings.provider, settings.consented) { provider, consented -> provider in consented }

    suspend fun allow(granted: Boolean) = settings.setConsent(settings.provider.first(), granted)

    suspend fun setProvider(provider: MapProvider) = settings.setProvider(provider)

    /** The place name of an item, looked up once and kept; null without the user's consent. */
    suspend fun placeName(assetId: String, point: GeoPoint): String? {
        store.placeName(assetId)?.let { return it }
        if (!allowed.first()) return null
        return places.nameOf(point)?.also { store.setPlaceName(assetId, it) }
    }
}
