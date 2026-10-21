package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CatalogDao {
    @Query(
        """SELECT v.volume_id AS volumeId, v.provider_volume_name AS providerVolumeName,
                  s.media_store_version AS mediaStoreVersion, s.last_generation AS lastGeneration
           FROM storage_volumes v LEFT JOIN catalog_sync_state s ON s.volume_id = v.volume_id""",
    )
    suspend fun volumes(): List<VolumeRow>

    @Query(
        """SELECT s.source_id AS sourceId, s.media_store_id AS mediaStoreId, a.media_kind AS mediaKind,
                  s.access_state AS accessState
           FROM media_sources s JOIN media_assets a ON a.asset_id = s.asset_id
           WHERE s.volume_id = :volumeId""",
    )
    suspend fun sources(volumeId: String): List<SourceRow>

    @Insert suspend fun insertVolumes(volumes: List<StorageVolumesEntity>)

    @Query("UPDATE storage_volumes SET state = :state WHERE volume_id IN (:volumeIds)")
    suspend fun setVolumeState(volumeIds: Collection<String>, state: String)

    @Query("UPDATE media_sources SET media_store_id = NULL WHERE source_id IN (:sourceIds)")
    suspend fun detach(sourceIds: Collection<String>)

    @Insert suspend fun insertDiscoveries(discoveries: List<MediaDiscoveriesEntity>)

    @Insert suspend fun insertAssets(assets: List<MediaAssetsEntity>)

    @Insert suspend fun insertResolutions(resolutions: List<IdentityResolutionsEntity>)

    @Insert suspend fun insertSources(sources: List<MediaSourcesEntity>)

    @Query(
        """UPDATE media_sources SET content_uri = :contentUri, display_name = :displayName,
                  relative_path = :relativePath, mime_type = :mimeType, file_size_bytes = :sizeBytes,
                  observed_modified_at = :modifiedAt, observed_taken_at = :takenAt,
                  observed_duration_ms = :durationMs, access_state = 'AVAILABLE'
           WHERE source_id = :sourceId""",
    )
    suspend fun refresh(
        sourceId: String,
        contentUri: String,
        displayName: String,
        relativePath: String?,
        mimeType: String,
        sizeBytes: Long,
        modifiedAt: Long?,
        takenAt: Long?,
        durationMs: Long?,
    )

    @Query("UPDATE media_sources SET access_state = :state WHERE source_id = :sourceId")
    suspend fun setAvailability(sourceId: String, state: String)

    @Upsert suspend fun upsertSyncStates(states: List<CatalogSyncStateEntity>)
}

data class VolumeRow(val volumeId: String, val providerVolumeName: String, val mediaStoreVersion: String?, val lastGeneration: Long?)

data class SourceRow(val sourceId: String, val mediaStoreId: Long?, val mediaKind: String, val accessState: String)
