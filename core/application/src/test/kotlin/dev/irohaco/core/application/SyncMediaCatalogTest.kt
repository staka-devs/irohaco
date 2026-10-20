package dev.irohaco.core.application

import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogStore
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.KnownSource
import dev.irohaco.core.contracts.MediaCatalogSource
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.domain.AssetAvailability
import dev.irohaco.core.domain.AssetAvailability.AVAILABLE
import dev.irohaco.core.domain.AssetAvailability.INACCESSIBLE
import dev.irohaco.core.domain.AssetAvailability.MISSING_CANDIDATE
import dev.irohaco.core.domain.AssetAvailability.OFFLINE
import dev.irohaco.core.domain.LibraryAccess.FULL
import dev.irohaco.core.domain.LibraryAccess.SELECTED
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncMediaCatalogTest {
    private val media = FakeMediaStore()
    private val store = FakeCatalogStore()
    private var access = MediaLibraryAccess(FULL, FULL)
    private var ids = 0
    private val sync = SyncMediaCatalog(media, { access }, store, { "id${++ids}" }, { 1_000L })

    @Test
    fun `first sync makes every visible item a new asset, and an unchanged library changes nothing`() = runTest {
        media.put(1, MediaKind.IMAGE)
        media.put(2, MediaKind.VIDEO)
        sync()
        assertEquals(2, store.assetCount)
        assertEquals(setOf(1L, 2L), store.sources.values.map { it.mediaStoreId }.toSet())
        assertEquals(1, store.volumes.size)

        val applied = store.applied.size
        sync()
        assertEquals(2, store.assetCount)
        assertEquals(applied + 1, store.applied.size)
        assertEquals(emptyList(), store.applied.last().newAssets)
        assertEquals(emptyMap(), store.applied.last().availability)
    }

    @Test
    fun `a modified item refreshes its source instead of creating an asset`() = runTest {
        media.put(1, MediaKind.IMAGE)
        sync()
        media.put(1, MediaKind.IMAGE, name = "renamed.jpg")
        sync()
        assertEquals(1, store.assetCount)
        assertEquals("renamed.jpg", store.applied.last().refreshedSources.values.single().displayName)
    }

    @Test
    fun `a removed item is a missing candidate under full access and inaccessible under partial access`() = runTest {
        media.put(1, MediaKind.IMAGE)
        media.put(2, MediaKind.VIDEO)
        access = MediaLibraryAccess(images = FULL, videos = SELECTED)
        sync()
        media.remove(1)
        media.remove(2)
        sync()
        assertEquals(MISSING_CANDIDATE, store.availabilityOf(1))
        assertEquals(INACCESSIBLE, store.availabilityOf(2))
        assertEquals(2, store.assetCount)
    }

    @Test
    fun `an item that becomes visible again is available again`() = runTest {
        media.put(1, MediaKind.IMAGE)
        sync()
        val hidden = media.hide(1)
        access = MediaLibraryAccess(SELECTED, SELECTED)
        sync()
        assertEquals(INACCESSIBLE, store.availabilityOf(1))
        media.show(hidden)
        sync()
        assertEquals(AVAILABLE, store.availabilityOf(1))
        assertEquals(1, store.assetCount)
    }

    @Test
    fun `an item added to a partial selection without a newer generation is still imported`() = runTest {
        media.put(1, MediaKind.IMAGE)
        val old = media.hide(2, MediaKind.IMAGE)
        access = MediaLibraryAccess(SELECTED, SELECTED)
        sync()
        media.show(old)
        sync()
        assertEquals(2, store.assetCount)
    }

    @Test
    fun `an unmounted volume and its sources go offline and come back`() = runTest {
        media.put(1, MediaKind.IMAGE, volume = "sd")
        sync()
        media.unmount("sd")
        sync()
        assertEquals("OFFLINE", store.volumeState("sd"))
        assertEquals(OFFLINE, store.availabilityOf(1))
        media.mount("sd")
        sync()
        assertEquals("ONLINE", store.volumeState("sd"))
        assertEquals(AVAILABLE, store.availabilityOf(1))
    }

    @Test
    fun `a new media store version detaches the old sources and imports the media as new assets`() = runTest {
        media.put(1, MediaKind.IMAGE)
        sync()
        val oldSource = store.sources.keys.single()
        media.version = "v2"
        sync()
        assertNull(store.sources.getValue(oldSource).mediaStoreId)
        assertEquals(MISSING_CANDIDATE, store.sources.getValue(oldSource).availability)
        assertEquals(2, store.assetCount)
    }
}

private class FakeMediaStore : MediaCatalogSource {
    private val volumesOnline = mutableSetOf<String>()
    private val entries = mutableMapOf<Pair<String, Long>, Pair<MediaStoreEntry, Long>>()
    private var generationCounter = 0L
    var version = "v1"

    fun put(id: Long, kind: MediaKind, volume: String = "external_primary", name: String = "$id.jpg") {
        volumesOnline += volume
        entries[volume to id] = entry(id, kind, name) to ++generationCounter
    }

    fun remove(id: Long) = entries.keys.removeIf { it.second == id }

    /** Hides an item without touching its generation, like a partial grant that leaves it out. */
    fun hide(id: Long, kind: MediaKind = MediaKind.IMAGE): Pair<Pair<String, Long>, Pair<MediaStoreEntry, Long>> {
        val key = "external_primary" to id
        val value = entries.remove(key) ?: (entry(id, kind, "$id.jpg") to ++generationCounter)
        volumesOnline += key.first
        return key to value
    }

    fun show(hidden: Pair<Pair<String, Long>, Pair<MediaStoreEntry, Long>>) {
        entries[hidden.first] = hidden.second
    }

    fun unmount(volume: String) = volumesOnline.remove(volume)

    fun mount(volume: String) = volumesOnline.add(volume)

    private fun entry(id: Long, kind: MediaKind, name: String) =
        MediaStoreEntry(id, kind, "content://media/$id", name, "DCIM/", "image/jpeg", 10, 1, 1)

    override suspend fun volumes() = volumesOnline.toSet()

    override suspend fun version(volume: String) = version

    override suspend fun generation(volume: String) = generationCounter

    override suspend fun changedSince(volume: String, sinceGeneration: Long) =
        entries.filter { it.key.first == volume && it.value.second > sinceGeneration }.map { it.value.first }

    override suspend fun visibleIds(volume: String) = entries.keys.filter { it.first == volume }.map { it.second }.toSet()

    override suspend fun entries(volume: String, ids: Set<Long>) =
        entries.filter { it.key.first == volume && it.key.second in ids }.map { it.value.first }
}

/** Applies CatalogChanges the way the Room store must. */
private class FakeCatalogStore : CatalogStore {
    data class Source(val volumeId: String, val mediaStoreId: Long?, val kind: MediaKind, val availability: AssetAvailability)

    val volumes = mutableListOf<CatalogVolume>()
    val volumeStates = mutableMapOf<String, String>()
    val sources = mutableMapOf<String, Source>()
    val applied = mutableListOf<CatalogChanges>()
    var assetCount = 0

    fun availabilityOf(mediaStoreId: Long) = sources.values.single { it.mediaStoreId == mediaStoreId }.availability

    fun volumeState(name: String) = volumeStates.getValue(volumes.single { it.providerVolumeName == name }.volumeId)

    override suspend fun volumes() = volumes.toList()

    override suspend fun knownSources(volumeId: String) =
        sources.filter { it.value.volumeId == volumeId }.map { (id, s) -> KnownSource(id, s.mediaStoreId, s.kind, s.availability) }

    override suspend fun apply(changes: CatalogChanges) {
        applied += changes
        volumes += changes.newVolumes
        changes.onlineVolumeIds.forEach { volumeStates[it] = "ONLINE" }
        changes.offlineVolumeIds.forEach { volumeStates[it] = "OFFLINE" }
        changes.detachedSourceIds.forEach { sources[it] = sources.getValue(it).copy(mediaStoreId = null) }
        changes.newAssets.forEach {
            check(sources.values.none { s -> s.volumeId == it.volumeId && s.mediaStoreId == it.entry.mediaStoreId }) { "UNIQUE(volume_id, media_store_id)" }
            sources[it.sourceId] = Source(it.volumeId, it.entry.mediaStoreId, it.entry.kind, AVAILABLE)
            assetCount++
        }
        changes.refreshedSources.keys.forEach { sources[it] = sources.getValue(it).copy(availability = AVAILABLE) }
        changes.availability.forEach { (id, a) -> sources[id] = sources.getValue(id).copy(availability = a) }
        changes.syncedVolumes.forEach { sync ->
            val i = volumes.indexOfFirst { it.volumeId == sync.volumeId }
            volumes[i] = volumes[i].copy(mediaStoreVersion = sync.mediaStoreVersion, lastGeneration = sync.generation)
        }
    }
}
