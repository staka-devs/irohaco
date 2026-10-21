package dev.irohaco.core.application

import dev.irohaco.core.contracts.LocationStore
import dev.irohaco.core.contracts.MapProvider
import dev.irohaco.core.contracts.MapSettings
import dev.irohaco.core.contracts.MediaDetailsReader
import dev.irohaco.core.contracts.PlaceNames
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Reads the GPS of each photo and video in batches, apart from the catalog sync (PRD GAL-017).
 * Cancelling the call pauses it; calling again resumes where it stopped, as the store remembers what
 * was read. Without the location permission it stops and reads nothing: the OS would hide the GPS.
 */
class IndexLocations(private val store: LocationStore, private val reader: MediaDetailsReader) {
    suspend operator fun invoke(batch: Int = 100, parallel: Int = 4) {
        // A read mostly waits on storage, so a few files at once go faster (M1-33).
        val gate = Semaphore(parallel)
        while (true) {
            val items = store.unindexed(batch)
            if (items.isEmpty()) return
            val read = coroutineScope {
                items.map { item -> async { gate.withPermit { item.assetId to reader.location(item.contentUri, item.kind) } } }.awaitAll()
            }
            if (read.any { (_, location) -> location == Detail.NoPermission }) return
            // No GPS, an unreadable one or a vanished file is kept as null, so it is not read on every resume.
            store.recordExif(read.associate { (assetId, location) -> assetId to (location as? Detail.Value)?.value })
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
