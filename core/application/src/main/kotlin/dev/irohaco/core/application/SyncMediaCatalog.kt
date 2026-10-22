package dev.irohaco.core.application

import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogStore
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.KnownSource
import dev.irohaco.core.contracts.MediaCatalogSource
import dev.irohaco.core.contracts.MediaLibraryAccessPort
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.VolumeSync
import dev.irohaco.core.domain.AssetAvailability
import dev.irohaco.core.domain.LibraryAccess
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Brings the catalog in line with MediaStore (ARCHITECTURE §6.1). Needs no user action and never
 * reads file contents, so it creates no media_revisions.
 *
 * - New media becomes a discovery resolved as NEW_ASSET; it is never merged into an existing asset.
 * - The same volume and MediaStore id is the same source: its observed fields are refreshed.
 * - Removals come from comparing visible ids, because the generation diff never reports them. A
 *   source that disappears is MISSING_CANDIDATE under full access and INACCESSIBLE otherwise.
 * - An unmounted volume and its sources go OFFLINE. No row is ever deleted.
 * - A changed MediaStore version means ids may be reused, so the volume's sources are detached
 *   and its media comes back as new assets (re-linking them is UC-ID-01's job, not this one).
 */
class SyncMediaCatalog(
    private val source: MediaCatalogSource,
    private val access: MediaLibraryAccessPort,
    private val store: CatalogStore,
    private val newId: () -> String,
    private val now: () -> Long,
) {
    // Two overlapping syncs would both see a new file as new and register it twice.
    private val running = Mutex()

    /** Returns whether a file is new or changed, which an index of changes would pick up (IDX-005). */
    suspend operator fun invoke(): Boolean = running.withLock {
        val access = access.current()
        val mounted = source.volumes()
        val known = store.volumes()
        val knownNames = known.map { it.providerVolumeName }.toSet()
        val newVolumes = (mounted - knownNames).sorted().map { CatalogVolume(newId(), it, null, null) }
        val offline = known.filter { it.providerVolumeName !in mounted }

        val result = Accumulator()
        for (volume in offline) {
            store.knownSources(volume.volumeId)
                .filter { it.availability == AssetAvailability.AVAILABLE }
                .forEach { result.availability[it.sourceId] = AssetAvailability.OFFLINE }
        }
        val online = known.filter { it.providerVolumeName in mounted } + newVolumes
        for (volume in online) {
            val sources = if (volume in newVolumes) emptyList() else store.knownSources(volume.volumeId)
            syncVolume(volume, sources, access, result)
        }

        store.apply(
            CatalogChanges(
                observedAtMs = now(),
                newVolumes = newVolumes,
                onlineVolumeIds = online.map { it.volumeId }.toSet(),
                offlineVolumeIds = offline.map { it.volumeId }.toSet(),
                detachedSourceIds = result.detached,
                newAssets = result.newAssets,
                refreshedSources = result.refreshed,
                availability = result.availability,
                syncedVolumes = result.synced,
            ),
        )
        result.newAssets.isNotEmpty() || result.refreshed.isNotEmpty()
    }

    private suspend fun syncVolume(
        volume: CatalogVolume,
        knownSources: List<KnownSource>,
        access: MediaLibraryAccess,
        result: Accumulator,
    ) {
        val name = volume.providerVolumeName
        val version = source.version(name)
        val generation = source.generation(name) // read before the diff so concurrent changes are seen next time

        val versionChanged = volume.mediaStoreVersion != null && volume.mediaStoreVersion != version
        val attached = if (versionChanged) {
            knownSources.filter { it.mediaStoreId != null }.forEach {
                result.detached += it.sourceId
                result.availability[it.sourceId] = AssetAvailability.MISSING_CANDIDATE
            }
            emptyMap()
        } else {
            knownSources.filter { it.mediaStoreId != null }.associateBy { it.mediaStoreId!! }
        }

        val since = if (versionChanged) 0L else volume.lastGeneration ?: 0L
        val changed = source.changedSince(name, since)
        val visible = source.visibleIds(name)
        val unseen = visible - attached.keys - changed.map { it.mediaStoreId }.toSet()
        val entries = changed + if (unseen.isEmpty()) emptyList() else source.entries(name, unseen)

        for (entry in entries) {
            val existing = attached[entry.mediaStoreId]
            if (existing != null) {
                result.refreshed[existing.sourceId] = entry
            } else {
                result.newAssets += newAsset(volume.volumeId, entry)
            }
        }
        for (known in attached.values) {
            if (known.sourceId in result.refreshed) continue
            val target = when {
                known.mediaStoreId in visible -> AssetAvailability.AVAILABLE
                access.of(known.kind) == LibraryAccess.FULL -> AssetAvailability.MISSING_CANDIDATE
                else -> AssetAvailability.INACCESSIBLE
            }
            if (target != known.availability) result.availability[known.sourceId] = target
        }
        result.synced += VolumeSync(volume.volumeId, version, generation)
    }

    private fun newAsset(volumeId: String, entry: MediaStoreEntry) =
        NewCatalogAsset(discoveryId = newId(), assetId = newId(), sourceId = newId(), volumeId = volumeId, entry = entry)

    private class Accumulator {
        val detached = mutableSetOf<String>()
        val newAssets = mutableListOf<NewCatalogAsset>()
        val refreshed = mutableMapOf<String, MediaStoreEntry>()
        val availability = mutableMapOf<String, AssetAvailability>()
        val synced = mutableListOf<VolumeSync>()
    }
}
