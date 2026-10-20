package dev.irohaco.core.database

import androidx.room.withTransaction
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogStore
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.KnownSource
import dev.irohaco.core.domain.AssetAvailability
import dev.irohaco.core.domain.MediaKind

class RoomCatalogStore(private val db: IrohacoDatabase) : CatalogStore {
    private val dao = db.catalogDao()

    override suspend fun volumes(): List<CatalogVolume> =
        dao.volumes().map { CatalogVolume(it.volumeId, it.providerVolumeName, it.mediaStoreVersion, it.lastGeneration) }

    override suspend fun knownSources(volumeId: String): List<KnownSource> =
        dao.sources(volumeId).map {
            KnownSource(it.sourceId, it.mediaStoreId, MediaKind.valueOf(it.mediaKind), AssetAvailability.valueOf(it.accessState))
        }

    /** One transaction, in foreign key order; detaching comes first so a reused MediaStore id can be inserted. */
    override suspend fun apply(changes: CatalogChanges) {
        val at = changes.observedAtMs
        db.withTransaction {
            dao.insertVolumes(changes.newVolumes.map { StorageVolumesEntity(it.volumeId, it.providerVolumeName, "ONLINE") })
            if (changes.onlineVolumeIds.isNotEmpty()) dao.setVolumeState(changes.onlineVolumeIds, "ONLINE")
            if (changes.offlineVolumeIds.isNotEmpty()) dao.setVolumeState(changes.offlineVolumeIds, "OFFLINE")
            if (changes.detachedSourceIds.isNotEmpty()) dao.detach(changes.detachedSourceIds)

            val added = changes.newAssets
            dao.insertDiscoveries(
                added.map {
                    MediaDiscoveriesEntity(it.discoveryId, it.volumeId, it.entry.mediaStoreId, it.entry.contentUri, it.entry.sizeBytes, at, "RESOLVED")
                },
            )
            dao.insertAssets(added.map { MediaAssetsEntity(it.assetId, it.entry.kind.name, at, null) })
            dao.insertResolutions(
                added.map { IdentityResolutionsEntity(it.discoveryId, "NEW_ASSET", it.assetId, "HIGH_CONFIDENCE_POLICY", at) },
            )
            dao.insertSources(
                added.map {
                    val e = it.entry
                    MediaSourcesEntity(
                        it.sourceId, it.assetId, it.volumeId, e.mediaStoreId, e.contentUri, e.displayName, e.relativePath,
                        e.mimeType, e.sizeBytes, e.modifiedAtMs, e.takenAtMs, AssetAvailability.AVAILABLE.name,
                    )
                },
            )

            changes.refreshedSources.forEach { (sourceId, e) ->
                dao.refresh(sourceId, e.contentUri, e.displayName, e.relativePath, e.mimeType, e.sizeBytes, e.modifiedAtMs, e.takenAtMs)
            }
            changes.availability.forEach { (sourceId, state) -> dao.setAvailability(sourceId, state.name) }
            dao.upsertSyncStates(changes.syncedVolumes.map { CatalogSyncStateEntity(it.volumeId, it.mediaStoreVersion, it.generation, at) })
        }
    }
}
