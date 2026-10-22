// Generated from database/schema.sql by database/generate_room_entities.py. Do not edit.
package dev.irohaco.core.database

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlbumItemsEntity::class,
        AlbumsEntity::class,
        AnnotationSubjectsEntity::class,
        AssetCurrentRevisionsEntity::class,
        AssetPrivacyRulesEntity::class,
        BackupArchiveEntriesEntity::class,
        BackupArchivesEntity::class,
        CatalogSyncStateEntity::class,
        EgressGrantsEntity::class,
        EmbeddingSpacesEntity::class,
        EmbeddingTargetsEntity::class,
        EmbeddingVectorsEntity::class,
        FrameExtractionProfilesEntity::class,
        IdentityCandidateSignalsEntity::class,
        IdentityCandidatesEntity::class,
        IdentityResolutionsEntity::class,
        IndexingJobItemsEntity::class,
        IndexingJobsEntity::class,
        LocationEvidenceEntity::class,
        LocationEvidenceSupportEntity::class,
        MediaAccessEventsEntity::class,
        MediaAssetsEntity::class,
        MediaDiscoveriesEntity::class,
        MediaRatingsEntity::class,
        MediaRevisionsEntity::class,
        MediaSourcesEntity::class,
        MediaTagsEntity::class,
        MetadataConflictsEntity::class,
        MetadataObservationsEntity::class,
        MetadataOverrideSetsEntity::class,
        MetadataOverrideValuesEntity::class,
        MetadataSyncBaselinesEntity::class,
        MetadataWriteOperationItemsEntity::class,
        MetadataWriteOperationsEntity::class,
        ModelCapabilitiesEntity::class,
        ModelProfilesEntity::class,
        OcrObservationsEntity::class,
        ProviderConnectionsEntity::class,
        RestoreAssetBindingsEntity::class,
        RestoreSessionsEntity::class,
        SampledFramesEntity::class,
        SavedSearchRootsEntity::class,
        SavedSearchesEntity::class,
        SearchProfileSpacesEntity::class,
        SearchProfilesEntity::class,
        SearchRuleNodesEntity::class,
        SearchRuleSetValuesEntity::class,
        SemanticAnnotationRevisionsEntity::class,
        SemanticAnnotationsEntity::class,
        SmartAlbumRulesEntity::class,
        StorageVolumesEntity::class,
        TagsEntity::class,
        TaskModelAssignmentsEntity::class,
        VideoSegmentsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class IrohacoDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun timelineDao(): TimelineDao
    abstract fun libraryDao(): LibraryDao
    abstract fun marksDao(): MarksDao
    abstract fun collectionsDao(): CollectionsDao
    abstract fun identityDao(): IdentityDao
    abstract fun locationDao(): LocationDao
    abstract fun modelProfileDao(): ModelProfileDao
    abstract fun indexingDao(): IndexingDao
}

@Entity(
    tableName = "album_items",
    primaryKeys = ["album_id", "asset_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = AlbumsEntity::class, parentColumns = ["album_id"], childColumns = ["album_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class AlbumItemsEntity(
    @ColumnInfo(name = "album_id") val albumId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
)

@Entity(
    tableName = "albums",
    primaryKeys = ["album_id"],
    foreignKeys = [
        ForeignKey(entity = AlbumsEntity::class, parentColumns = ["album_id"], childColumns = ["parent_album_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class AlbumsEntity(
    @ColumnInfo(name = "album_id") val albumId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "album_kind") val albumKind: String,
    @ColumnInfo(name = "parent_album_id") val parentAlbumId: String?,
)

@Entity(
    tableName = "annotation_subjects",
    primaryKeys = ["subject_id"],
    foreignKeys = [
        ForeignKey(entity = VideoSegmentsEntity::class, parentColumns = ["segment_id"], childColumns = ["segment_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["asset_id"], name = "ux_annotation_subject_asset", unique = true),
        Index(value = ["segment_id"], name = "ux_annotation_subject_segment", unique = true),
    ],
)
data class AnnotationSubjectsEntity(
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    @ColumnInfo(name = "segment_id") val segmentId: String?,
)

@Entity(
    tableName = "asset_current_revisions",
    primaryKeys = ["asset_id"],
    foreignKeys = [
        ForeignKey(entity = MediaRevisionsEntity::class, parentColumns = ["asset_id", "revision_id"], childColumns = ["asset_id", "revision_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class AssetCurrentRevisionsEntity(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "revision_id") val revisionId: String,
)

@Entity(
    tableName = "asset_privacy_rules",
    primaryKeys = ["asset_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class AssetPrivacyRulesEntity(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "allow_ai_indexing", defaultValue = "1") val allowAiIndexing: Long,
    @ColumnInfo(name = "allow_remote_send", defaultValue = "0") val allowRemoteSend: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "backup_archive_entries",
    primaryKeys = ["backup_id", "entity_kind", "exported_entity_id"],
    foreignKeys = [
        ForeignKey(entity = BackupArchivesEntity::class, parentColumns = ["backup_id"], childColumns = ["backup_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class BackupArchiveEntriesEntity(
    @ColumnInfo(name = "backup_id") val backupId: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    @ColumnInfo(name = "exported_entity_id") val exportedEntityId: String,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
)

@Entity(
    tableName = "backup_archives",
    primaryKeys = ["backup_id"],
)
data class BackupArchivesEntity(
    @ColumnInfo(name = "backup_id") val backupId: String,
    @ColumnInfo(name = "format_version") val formatVersion: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "archive_ref") val archiveRef: String,
    @ColumnInfo(name = "manifest_sha256") val manifestSha256: String,
    @ColumnInfo(name = "encryption_code") val encryptionCode: String,
    @ColumnInfo(name = "state") val state: String,
)

@Entity(
    tableName = "catalog_sync_state",
    primaryKeys = ["volume_id"],
    foreignKeys = [
        ForeignKey(entity = StorageVolumesEntity::class, parentColumns = ["volume_id"], childColumns = ["volume_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class CatalogSyncStateEntity(
    @ColumnInfo(name = "volume_id") val volumeId: String,
    @ColumnInfo(name = "media_store_version") val mediaStoreVersion: String?,
    @ColumnInfo(name = "last_generation") val lastGeneration: Long?,
    @ColumnInfo(name = "last_successful_sync_at") val lastSuccessfulSyncAt: Long?,
)

@Entity(
    tableName = "egress_grants",
    primaryKeys = ["connection_id", "task_code", "payload_type"],
    foreignKeys = [
        ForeignKey(entity = ProviderConnectionsEntity::class, parentColumns = ["connection_id"], childColumns = ["connection_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class EgressGrantsEntity(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "task_code") val taskCode: String,
    @ColumnInfo(name = "payload_type") val payloadType: String,
    @ColumnInfo(name = "granted_at") val grantedAt: Long,
)

@Entity(
    tableName = "embedding_spaces",
    primaryKeys = ["space_id"],
    foreignKeys = [
        ForeignKey(entity = ModelProfilesEntity::class, parentColumns = ["model_profile_id"], childColumns = ["model_profile_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class EmbeddingSpacesEntity(
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "model_profile_id") val modelProfileId: String,
    @ColumnInfo(name = "dimension") val dimension: Long,
    @ColumnInfo(name = "value_format", defaultValue = "'FLOAT32_LE'") val valueFormat: String,
    @ColumnInfo(name = "normalization") val normalization: String,
    @ColumnInfo(name = "query_prefix") val queryPrefix: String?,
    @ColumnInfo(name = "corpus_prefix") val corpusPrefix: String?,
    @ColumnInfo(name = "preprocessing_signature") val preprocessingSignature: String,
    @ColumnInfo(name = "space_signature") val spaceSignature: String,
    @ColumnInfo(name = "lifecycle_state") val lifecycleState: String,
)

@Entity(
    tableName = "embedding_targets",
    primaryKeys = ["target_id"],
    foreignKeys = [
        ForeignKey(entity = OcrObservationsEntity::class, parentColumns = ["ocr_id"], childColumns = ["ocr_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = SemanticAnnotationsEntity::class, parentColumns = ["annotation_id"], childColumns = ["annotation_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = SampledFramesEntity::class, parentColumns = ["frame_id"], childColumns = ["frame_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["annotation_id"], name = "ux_embed_target_annotation", unique = true),
        Index(value = ["asset_id"], name = "ux_embed_target_asset", unique = true),
        Index(value = ["frame_id"], name = "ux_embed_target_frame", unique = true),
        Index(value = ["ocr_id"], name = "ux_embed_target_ocr", unique = true),
    ],
)
data class EmbeddingTargetsEntity(
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    @ColumnInfo(name = "frame_id") val frameId: String?,
    @ColumnInfo(name = "annotation_id") val annotationId: String?,
    @ColumnInfo(name = "ocr_id") val ocrId: String?,
)

@Entity(
    tableName = "embedding_vectors",
    primaryKeys = ["vector_id"],
    foreignKeys = [
        ForeignKey(entity = EmbeddingTargetsEntity::class, parentColumns = ["target_id"], childColumns = ["target_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = EmbeddingSpacesEntity::class, parentColumns = ["space_id"], childColumns = ["space_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["space_id", "target_id"], name = "ix_embed_vectors_space_target", unique = false),
    ],
)
data class EmbeddingVectorsEntity(
    @ColumnInfo(name = "vector_id") val vectorId: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "source_revision_digest") val sourceRevisionDigest: String,
    @ColumnInfo(name = "vector_blob") val vectorBlob: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "frame_extraction_profiles",
    primaryKeys = ["extraction_profile_id"],
)
data class FrameExtractionProfilesEntity(
    @ColumnInfo(name = "extraction_profile_id") val extractionProfileId: String,
    @ColumnInfo(name = "strategy") val strategy: String,
    @ColumnInfo(name = "interval_ms") val intervalMs: Long,
    @ColumnInfo(name = "max_frames") val maxFrames: Long,
    @ColumnInfo(name = "profile_digest") val profileDigest: String,
)

@Entity(
    tableName = "identity_candidate_signals",
    primaryKeys = ["discovery_id", "asset_id", "signal_code"],
    foreignKeys = [
        ForeignKey(entity = IdentityCandidatesEntity::class, parentColumns = ["discovery_id", "asset_id"], childColumns = ["discovery_id", "asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class IdentityCandidateSignalsEntity(
    @ColumnInfo(name = "discovery_id") val discoveryId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "signal_code") val signalCode: String,
    @ColumnInfo(name = "signal_digest") val signalDigest: String?,
)

@Entity(
    tableName = "identity_candidates",
    primaryKeys = ["discovery_id", "asset_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaDiscoveriesEntity::class, parentColumns = ["discovery_id"], childColumns = ["discovery_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class IdentityCandidatesEntity(
    @ColumnInfo(name = "discovery_id") val discoveryId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "candidate_state") val candidateState: String,
)

@Entity(
    tableName = "identity_resolutions",
    primaryKeys = ["discovery_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["selected_asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaDiscoveriesEntity::class, parentColumns = ["discovery_id"], childColumns = ["discovery_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class IdentityResolutionsEntity(
    @ColumnInfo(name = "discovery_id") val discoveryId: String,
    @ColumnInfo(name = "resolution_kind") val resolutionKind: String,
    @ColumnInfo(name = "selected_asset_id") val selectedAssetId: String?,
    @ColumnInfo(name = "decided_by") val decidedBy: String,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long,
)

@Entity(
    tableName = "indexing_job_items",
    primaryKeys = ["job_id", "target_id", "source_revision_digest"],
    foreignKeys = [
        ForeignKey(entity = EmbeddingTargetsEntity::class, parentColumns = ["target_id"], childColumns = ["target_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = IndexingJobsEntity::class, parentColumns = ["job_id"], childColumns = ["job_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["job_id", "state"], name = "ix_job_items_state", unique = false),
    ],
)
data class IndexingJobItemsEntity(
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "source_revision_digest") val sourceRevisionDigest: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Long,
)

@Entity(
    tableName = "indexing_jobs",
    primaryKeys = ["job_id"],
    foreignKeys = [
        ForeignKey(entity = EmbeddingSpacesEntity::class, parentColumns = ["space_id"], childColumns = ["space_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class IndexingJobsEntity(
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "job_kind") val jobKind: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "requested_by_user") val requestedByUser: Long,
    @ColumnInfo(name = "requested_at") val requestedAt: Long,
)

@Entity(
    tableName = "location_evidence",
    primaryKeys = ["evidence_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["asset_id", "evidence_status"], name = "ix_location_asset_status", unique = false),
    ],
)
data class LocationEvidenceEntity(
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "evidence_source") val evidenceSource: String,
    @ColumnInfo(name = "evidence_status") val evidenceStatus: String,
    @ColumnInfo(name = "latitude") val latitude: Double?,
    @ColumnInfo(name = "longitude") val longitude: Double?,
    @ColumnInfo(name = "place_text") val placeText: String?,
    @ColumnInfo(name = "confidence") val confidence: Double?,
    @ColumnInfo(name = "explanation", defaultValue = "''") val explanation: String,
)

@Entity(
    tableName = "location_evidence_support",
    primaryKeys = ["evidence_id", "supporting_asset_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["supporting_asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = LocationEvidenceEntity::class, parentColumns = ["evidence_id"], childColumns = ["evidence_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class LocationEvidenceSupportEntity(
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "supporting_asset_id") val supportingAssetId: String,
)

@Entity(
    tableName = "media_access_events",
    primaryKeys = ["access_event_id"],
    foreignKeys = [
        ForeignKey(entity = MediaSourcesEntity::class, parentColumns = ["source_id"], childColumns = ["source_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["asset_id", "observed_at"], name = "ix_access_events_asset", unique = false),
    ],
)
data class MediaAccessEventsEntity(
    @ColumnInfo(name = "access_event_id") val accessEventId: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    @ColumnInfo(name = "source_id") val sourceId: String?,
    @ColumnInfo(name = "access_event_code") val accessEventCode: String,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
)

@Entity(
    tableName = "media_assets",
    primaryKeys = ["asset_id"],
)
data class MediaAssetsEntity(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "media_kind") val mediaKind: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "tombstoned_at") val tombstonedAt: Long?,
)

@Entity(
    tableName = "media_discoveries",
    primaryKeys = ["discovery_id"],
    foreignKeys = [
        ForeignKey(entity = StorageVolumesEntity::class, parentColumns = ["volume_id"], childColumns = ["volume_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["volume_id", "provider_media_store_id"], name = "ix_discovery_volume", unique = false),
    ],
)
data class MediaDiscoveriesEntity(
    @ColumnInfo(name = "discovery_id") val discoveryId: String,
    @ColumnInfo(name = "volume_id") val volumeId: String,
    @ColumnInfo(name = "provider_media_store_id") val providerMediaStoreId: Long?,
    @ColumnInfo(name = "observed_uri") val observedUri: String,
    @ColumnInfo(name = "observed_size_bytes") val observedSizeBytes: Long,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    @ColumnInfo(name = "discovery_state") val discoveryState: String,
)

@Entity(
    tableName = "media_ratings",
    primaryKeys = ["asset_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class MediaRatingsEntity(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "rating") val rating: Long?,
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Long,
)

@Entity(
    tableName = "media_revisions",
    primaryKeys = ["revision_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["asset_id", "revision_id"], name = "ux_media_revisions_asset_revision", unique = true),
    ],
)
data class MediaRevisionsEntity(
    @ColumnInfo(name = "revision_id") val revisionId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "file_digest") val fileDigest: String,
    @ColumnInfo(name = "visual_digest") val visualDigest: String?,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    @ColumnInfo(name = "width_px") val widthPx: Long?,
    @ColumnInfo(name = "height_px") val heightPx: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
)

@Entity(
    tableName = "media_sources",
    primaryKeys = ["source_id"],
    foreignKeys = [
        ForeignKey(entity = StorageVolumesEntity::class, parentColumns = ["volume_id"], childColumns = ["volume_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["access_state"], name = "ix_media_sources_access", unique = false),
        Index(value = ["asset_id"], name = "ix_media_sources_asset", unique = false),
    ],
)
data class MediaSourcesEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "volume_id") val volumeId: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long?,
    @ColumnInfo(name = "content_uri") val contentUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long,
    @ColumnInfo(name = "observed_modified_at") val observedModifiedAt: Long?,
    @ColumnInfo(name = "observed_taken_at") val observedTakenAt: Long?,
    @ColumnInfo(name = "observed_duration_ms") val observedDurationMs: Long?,
    @ColumnInfo(name = "access_state") val accessState: String,
)

@Entity(
    tableName = "media_tags",
    primaryKeys = ["asset_id", "tag_id"],
    foreignKeys = [
        ForeignKey(entity = TagsEntity::class, parentColumns = ["tag_id"], childColumns = ["tag_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["tag_id"], name = "ix_media_tags_tag", unique = false),
    ],
)
data class MediaTagsEntity(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
)

@Entity(
    tableName = "metadata_conflicts",
    primaryKeys = ["conflict_id"],
    foreignKeys = [
        ForeignKey(entity = MediaRevisionsEntity::class, parentColumns = ["revision_id"], childColumns = ["remote_revision_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaRevisionsEntity::class, parentColumns = ["revision_id"], childColumns = ["base_revision_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MetadataOverrideSetsEntity::class, parentColumns = ["override_id"], childColumns = ["override_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["override_id", "detected_at"], name = "ix_metadata_conflicts_open", unique = false),
    ],
)
data class MetadataConflictsEntity(
    @ColumnInfo(name = "conflict_id") val conflictId: String,
    @ColumnInfo(name = "override_id") val overrideId: String,
    @ColumnInfo(name = "base_revision_id") val baseRevisionId: String,
    @ColumnInfo(name = "remote_revision_id") val remoteRevisionId: String,
    @ColumnInfo(name = "observed_local_revision_no") val observedLocalRevisionNo: Long,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
    @ColumnInfo(name = "resolution_code") val resolutionCode: String?,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long?,
)

@Entity(
    tableName = "metadata_observations",
    primaryKeys = ["revision_id", "namespace", "tag_name", "occurrence"],
    foreignKeys = [
        ForeignKey(entity = MediaRevisionsEntity::class, parentColumns = ["revision_id"], childColumns = ["revision_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class MetadataObservationsEntity(
    @ColumnInfo(name = "revision_id") val revisionId: String,
    @ColumnInfo(name = "namespace") val namespace: String,
    @ColumnInfo(name = "tag_name") val tagName: String,
    @ColumnInfo(name = "occurrence", defaultValue = "0") val occurrence: Long,
    @ColumnInfo(name = "text_value") val textValue: String?,
    @ColumnInfo(name = "int_value") val intValue: Long?,
    @ColumnInfo(name = "real_value") val realValue: Double?,
    @ColumnInfo(name = "blob_value") val blobValue: ByteArray?,
)

@Entity(
    tableName = "metadata_override_sets",
    primaryKeys = ["override_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class MetadataOverrideSetsEntity(
    @ColumnInfo(name = "override_id") val overrideId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "namespace") val namespace: String,
    @ColumnInfo(name = "tag_name") val tagName: String,
    @ColumnInfo(name = "edit_mode") val editMode: String,
    @ColumnInfo(name = "local_revision_no") val localRevisionNo: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
)

@Entity(
    tableName = "metadata_override_values",
    primaryKeys = ["override_id", "occurrence"],
    foreignKeys = [
        ForeignKey(entity = MetadataOverrideSetsEntity::class, parentColumns = ["override_id"], childColumns = ["override_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class MetadataOverrideValuesEntity(
    @ColumnInfo(name = "override_id") val overrideId: String,
    @ColumnInfo(name = "occurrence") val occurrence: Long,
    @ColumnInfo(name = "text_value") val textValue: String?,
    @ColumnInfo(name = "int_value") val intValue: Long?,
    @ColumnInfo(name = "real_value") val realValue: Double?,
    @ColumnInfo(name = "blob_value") val blobValue: ByteArray?,
)

@Entity(
    tableName = "metadata_sync_baselines",
    primaryKeys = ["override_id"],
    foreignKeys = [
        ForeignKey(entity = MediaRevisionsEntity::class, parentColumns = ["revision_id"], childColumns = ["base_revision_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MetadataOverrideSetsEntity::class, parentColumns = ["override_id"], childColumns = ["override_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class MetadataSyncBaselinesEntity(
    @ColumnInfo(name = "override_id") val overrideId: String,
    @ColumnInfo(name = "base_revision_id") val baseRevisionId: String,
    @ColumnInfo(name = "base_local_revision_no") val baseLocalRevisionNo: Long,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
)

@Entity(
    tableName = "metadata_write_operation_items",
    primaryKeys = ["operation_id", "override_id"],
    foreignKeys = [
        ForeignKey(entity = MetadataOverrideSetsEntity::class, parentColumns = ["override_id"], childColumns = ["override_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MetadataWriteOperationsEntity::class, parentColumns = ["operation_id"], childColumns = ["operation_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class MetadataWriteOperationItemsEntity(
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "override_id") val overrideId: String,
    @ColumnInfo(name = "written_local_revision_no") val writtenLocalRevisionNo: Long,
)

@Entity(
    tableName = "metadata_write_operations",
    primaryKeys = ["operation_id"],
    foreignKeys = [
        ForeignKey(entity = MediaRevisionsEntity::class, parentColumns = ["asset_id", "revision_id"], childColumns = ["asset_id", "after_revision_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaRevisionsEntity::class, parentColumns = ["asset_id", "revision_id"], childColumns = ["asset_id", "before_revision_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class MetadataWriteOperationsEntity(
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "before_revision_id") val beforeRevisionId: String?,
    @ColumnInfo(name = "after_revision_id") val afterRevisionId: String?,
    @ColumnInfo(name = "operation_state") val operationState: String,
    @ColumnInfo(name = "backup_artifact_ref") val backupArtifactRef: String?,
    @ColumnInfo(name = "requested_at") val requestedAt: Long,
)

@Entity(
    tableName = "model_capabilities",
    primaryKeys = ["model_profile_id", "capability_code"],
    foreignKeys = [
        ForeignKey(entity = ModelProfilesEntity::class, parentColumns = ["model_profile_id"], childColumns = ["model_profile_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class ModelCapabilitiesEntity(
    @ColumnInfo(name = "model_profile_id") val modelProfileId: String,
    @ColumnInfo(name = "capability_code") val capabilityCode: String,
)

@Entity(
    tableName = "model_profiles",
    primaryKeys = ["model_profile_id"],
    foreignKeys = [
        ForeignKey(entity = ProviderConnectionsEntity::class, parentColumns = ["connection_id"], childColumns = ["connection_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class ModelProfilesEntity(
    @ColumnInfo(name = "model_profile_id") val modelProfileId: String,
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "provider_model_id") val providerModelId: String,
    @ColumnInfo(name = "artifact_revision") val artifactRevision: String?,
    @ColumnInfo(name = "display_name") val displayName: String,
)

@Entity(
    tableName = "ocr_observations",
    primaryKeys = ["ocr_id"],
    foreignKeys = [
        ForeignKey(entity = SampledFramesEntity::class, parentColumns = ["frame_id"], childColumns = ["frame_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["asset_id"], name = "ix_ocr_asset", unique = false),
        Index(value = ["frame_id"], name = "ix_ocr_frame", unique = false),
    ],
)
data class OcrObservationsEntity(
    @ColumnInfo(name = "ocr_id") val ocrId: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    @ColumnInfo(name = "frame_id") val frameId: String?,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "engine_revision") val engineRevision: String,
)

@Entity(
    tableName = "provider_connections",
    primaryKeys = ["connection_id"],
)
data class ProviderConnectionsEntity(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "protocol") val protocol: String,
    @ColumnInfo(name = "base_url") val baseUrl: String?,
    @ColumnInfo(name = "credential_ref") val credentialRef: String?,
)

@Entity(
    tableName = "restore_asset_bindings",
    primaryKeys = ["restore_id", "exported_asset_id"],
    foreignKeys = [
        ForeignKey(entity = MediaAssetsEntity::class, parentColumns = ["asset_id"], childColumns = ["local_asset_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = RestoreSessionsEntity::class, parentColumns = ["restore_id"], childColumns = ["restore_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["local_asset_id"], name = "ix_restore_bindings_target", unique = false),
    ],
)
data class RestoreAssetBindingsEntity(
    @ColumnInfo(name = "restore_id") val restoreId: String,
    @ColumnInfo(name = "exported_asset_id") val exportedAssetId: String,
    @ColumnInfo(name = "local_asset_id") val localAssetId: String?,
    @ColumnInfo(name = "match_decision") val matchDecision: String,
)

@Entity(
    tableName = "restore_sessions",
    primaryKeys = ["restore_id"],
    foreignKeys = [
        ForeignKey(entity = BackupArchivesEntity::class, parentColumns = ["backup_id"], childColumns = ["backup_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class RestoreSessionsEntity(
    @ColumnInfo(name = "restore_id") val restoreId: String,
    @ColumnInfo(name = "backup_id") val backupId: String,
    @ColumnInfo(name = "restore_state") val restoreState: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
)

@Entity(
    tableName = "sampled_frames",
    primaryKeys = ["frame_id"],
    foreignKeys = [
        ForeignKey(entity = VideoSegmentsEntity::class, parentColumns = ["segment_id"], childColumns = ["segment_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["segment_id", "timestamp_ms"], name = "ix_sampled_frames_segment", unique = false),
    ],
)
data class SampledFramesEntity(
    @ColumnInfo(name = "frame_id") val frameId: String,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "frame_digest") val frameDigest: String,
)

@Entity(
    tableName = "saved_search_roots",
    primaryKeys = ["saved_search_id"],
    foreignKeys = [
        ForeignKey(entity = SearchRuleNodesEntity::class, parentColumns = ["rule_node_id"], childColumns = ["root_node_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = SavedSearchesEntity::class, parentColumns = ["saved_search_id"], childColumns = ["saved_search_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class SavedSearchRootsEntity(
    @ColumnInfo(name = "saved_search_id") val savedSearchId: String,
    @ColumnInfo(name = "root_node_id") val rootNodeId: String,
)

@Entity(
    tableName = "saved_searches",
    primaryKeys = ["saved_search_id"],
    foreignKeys = [
        ForeignKey(entity = SearchProfilesEntity::class, parentColumns = ["search_profile_id"], childColumns = ["search_profile_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class SavedSearchesEntity(
    @ColumnInfo(name = "saved_search_id") val savedSearchId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "search_profile_id") val searchProfileId: String,
)

@Entity(
    tableName = "search_profile_spaces",
    primaryKeys = ["search_profile_id", "space_id"],
    foreignKeys = [
        ForeignKey(entity = EmbeddingSpacesEntity::class, parentColumns = ["space_id"], childColumns = ["space_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = SearchProfilesEntity::class, parentColumns = ["search_profile_id"], childColumns = ["search_profile_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class SearchProfileSpacesEntity(
    @ColumnInfo(name = "search_profile_id") val searchProfileId: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "priority") val priority: Long,
)

@Entity(
    tableName = "search_profiles",
    primaryKeys = ["search_profile_id"],
)
data class SearchProfilesEntity(
    @ColumnInfo(name = "search_profile_id") val searchProfileId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "fusion_strategy") val fusionStrategy: String,
)

@Entity(
    tableName = "search_rule_nodes",
    primaryKeys = ["rule_node_id"],
    foreignKeys = [
        ForeignKey(entity = SearchRuleNodesEntity::class, parentColumns = ["rule_node_id"], childColumns = ["parent_node_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["parent_node_id", "sibling_order"], name = "ux_rule_sibling", unique = true),
    ],
)
data class SearchRuleNodesEntity(
    @ColumnInfo(name = "rule_node_id") val ruleNodeId: String,
    @ColumnInfo(name = "parent_node_id") val parentNodeId: String?,
    @ColumnInfo(name = "sibling_order") val siblingOrder: Long,
    @ColumnInfo(name = "node_kind") val nodeKind: String,
    @ColumnInfo(name = "field_code") val fieldCode: String?,
    @ColumnInfo(name = "comparator") val comparator: String?,
    @ColumnInfo(name = "scalar_value") val scalarValue: String?,
)

@Entity(
    tableName = "search_rule_set_values",
    primaryKeys = ["rule_node_id", "value"],
    foreignKeys = [
        ForeignKey(entity = SearchRuleNodesEntity::class, parentColumns = ["rule_node_id"], childColumns = ["rule_node_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class SearchRuleSetValuesEntity(
    @ColumnInfo(name = "rule_node_id") val ruleNodeId: String,
    @ColumnInfo(name = "value") val value: String,
)

@Entity(
    tableName = "semantic_annotation_revisions",
    primaryKeys = ["annotation_id", "revision_no"],
    foreignKeys = [
        ForeignKey(entity = ModelProfilesEntity::class, parentColumns = ["model_profile_id"], childColumns = ["model_profile_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = SemanticAnnotationsEntity::class, parentColumns = ["annotation_id"], childColumns = ["annotation_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class SemanticAnnotationRevisionsEntity(
    @ColumnInfo(name = "annotation_id") val annotationId: String,
    @ColumnInfo(name = "revision_no") val revisionNo: Long,
    @ColumnInfo(name = "author") val author: String,
    @ColumnInfo(name = "model_profile_id") val modelProfileId: String?,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
)

@Entity(
    tableName = "semantic_annotations",
    primaryKeys = ["annotation_id"],
    foreignKeys = [
        ForeignKey(entity = AnnotationSubjectsEntity::class, parentColumns = ["subject_id"], childColumns = ["subject_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["subject_id"], name = "ix_semantic_annotations_subject", unique = false),
    ],
)
data class SemanticAnnotationsEntity(
    @ColumnInfo(name = "annotation_id") val annotationId: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "annotation_kind") val annotationKind: String,
    @ColumnInfo(name = "searchable", defaultValue = "1") val searchable: Long,
    @ColumnInfo(name = "exportable", defaultValue = "1") val exportable: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "smart_album_rules",
    primaryKeys = ["album_id"],
    foreignKeys = [
        ForeignKey(entity = SavedSearchesEntity::class, parentColumns = ["saved_search_id"], childColumns = ["saved_search_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = AlbumsEntity::class, parentColumns = ["album_id"], childColumns = ["album_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class SmartAlbumRulesEntity(
    @ColumnInfo(name = "album_id") val albumId: String,
    @ColumnInfo(name = "saved_search_id") val savedSearchId: String,
)

@Entity(
    tableName = "storage_volumes",
    primaryKeys = ["volume_id"],
)
data class StorageVolumesEntity(
    @ColumnInfo(name = "volume_id") val volumeId: String,
    @ColumnInfo(name = "provider_volume_name") val providerVolumeName: String,
    @ColumnInfo(name = "state") val state: String,
)

@Entity(
    tableName = "tags",
    primaryKeys = ["tag_id"],
    foreignKeys = [
        ForeignKey(entity = TagsEntity::class, parentColumns = ["tag_id"], childColumns = ["parent_tag_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["name"], name = "ux_root_tags_name", unique = true),
    ],
)
data class TagsEntity(
    @ColumnInfo(name = "tag_id") val tagId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "parent_tag_id") val parentTagId: String?,
)

@Entity(
    tableName = "task_model_assignments",
    primaryKeys = ["task_code"],
    foreignKeys = [
        ForeignKey(entity = ModelProfilesEntity::class, parentColumns = ["model_profile_id"], childColumns = ["model_profile_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
)
data class TaskModelAssignmentsEntity(
    @ColumnInfo(name = "task_code") val taskCode: String,
    @ColumnInfo(name = "model_profile_id") val modelProfileId: String,
)

@Entity(
    tableName = "video_segments",
    primaryKeys = ["segment_id"],
    foreignKeys = [
        ForeignKey(entity = FrameExtractionProfilesEntity::class, parentColumns = ["extraction_profile_id"], childColumns = ["extraction_profile_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
        ForeignKey(entity = MediaRevisionsEntity::class, parentColumns = ["revision_id"], childColumns = ["revision_id"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["revision_id", "start_ms"], name = "ix_video_segments_revision", unique = false),
    ],
)
data class VideoSegmentsEntity(
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "revision_id") val revisionId: String,
    @ColumnInfo(name = "extraction_profile_id") val extractionProfileId: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
)
