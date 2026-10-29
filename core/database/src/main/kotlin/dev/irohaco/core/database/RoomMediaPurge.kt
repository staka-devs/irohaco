package dev.irohaco.core.database

import androidx.room.withTransaction
import dev.irohaco.core.contracts.DeletedMediaStore

private const val ASSETS = "(SELECT asset_id FROM purge_assets)"
private const val REVISIONS = "(SELECT revision_id FROM media_revisions WHERE asset_id IN $ASSETS)"
private const val SEGMENTS = "(SELECT segment_id FROM video_segments WHERE revision_id IN $REVISIONS)"
private const val FRAMES = "(SELECT frame_id FROM sampled_frames WHERE segment_id IN $SEGMENTS)"
private const val SUBJECTS = "(SELECT subject_id FROM annotation_subjects WHERE asset_id IN $ASSETS OR segment_id IN $SEGMENTS)"
private const val ANNOTATIONS = "(SELECT annotation_id FROM semantic_annotations WHERE subject_id IN $SUBJECTS)"
private const val OCRS = "(SELECT ocr_id FROM ocr_observations WHERE asset_id IN $ASSETS OR frame_id IN $FRAMES)"
private const val TARGETS =
    """(SELECT target_id FROM embedding_targets WHERE asset_id IN $ASSETS OR frame_id IN $FRAMES
         OR annotation_id IN $ANNOTATIONS OR ocr_id IN $OCRS)"""
private const val OVERRIDES = "(SELECT override_id FROM metadata_override_sets WHERE asset_id IN $ASSETS)"
private const val OPERATIONS = "(SELECT operation_id FROM metadata_write_operations WHERE asset_id IN $ASSETS)"
private const val DISCOVERIES = "(SELECT discovery_id FROM purge_discoveries)"
private const val SOURCES = "(SELECT source_id FROM purge_sources)"

/** Children before parents, as every foreign key is RESTRICT. */
private val DELETES = listOf(
    "DELETE FROM embedding_vectors WHERE target_id IN $TARGETS",
    "DELETE FROM indexing_job_items WHERE target_id IN $TARGETS",
    "DELETE FROM embedding_targets WHERE target_id IN $TARGETS",
    "DELETE FROM ocr_fts WHERE docid IN (SELECT rowid FROM ocr_observations WHERE ocr_id IN $OCRS)",
    "DELETE FROM ocr_observations WHERE ocr_id IN $OCRS",
    "DELETE FROM annotation_fts WHERE docid IN (SELECT rowid FROM semantic_annotations WHERE annotation_id IN $ANNOTATIONS)",
    "DELETE FROM semantic_annotation_revisions WHERE annotation_id IN $ANNOTATIONS",
    "DELETE FROM semantic_annotations WHERE annotation_id IN $ANNOTATIONS",
    "DELETE FROM annotation_subjects WHERE subject_id IN $SUBJECTS",
    "DELETE FROM sampled_frames WHERE frame_id IN $FRAMES",
    "DELETE FROM metadata_write_operation_items WHERE operation_id IN $OPERATIONS OR override_id IN $OVERRIDES",
    "DELETE FROM metadata_write_operations WHERE asset_id IN $ASSETS",
    "DELETE FROM metadata_conflicts WHERE override_id IN $OVERRIDES",
    "DELETE FROM metadata_sync_baselines WHERE override_id IN $OVERRIDES",
    "DELETE FROM metadata_override_values WHERE override_id IN $OVERRIDES",
    "DELETE FROM metadata_override_sets WHERE asset_id IN $ASSETS",
    "DELETE FROM metadata_observations WHERE revision_id IN $REVISIONS",
    "DELETE FROM video_segments WHERE segment_id IN $SEGMENTS",
    "DELETE FROM asset_current_revisions WHERE asset_id IN $ASSETS",
    "DELETE FROM media_revisions WHERE asset_id IN $ASSETS",
    "DELETE FROM media_tags WHERE asset_id IN $ASSETS",
    "DELETE FROM media_ratings WHERE asset_id IN $ASSETS",
    "DELETE FROM album_items WHERE asset_id IN $ASSETS",
    """DELETE FROM location_evidence_support WHERE supporting_asset_id IN $ASSETS
         OR evidence_id IN (SELECT evidence_id FROM location_evidence WHERE asset_id IN $ASSETS)""",
    "DELETE FROM location_evidence WHERE asset_id IN $ASSETS",
    "DELETE FROM identity_candidate_signals WHERE discovery_id IN $DISCOVERIES OR asset_id IN $ASSETS",
    "DELETE FROM identity_candidates WHERE discovery_id IN $DISCOVERIES OR asset_id IN $ASSETS",
    "DELETE FROM identity_resolutions WHERE discovery_id IN $DISCOVERIES",
    "DELETE FROM media_discoveries WHERE discovery_id IN $DISCOVERIES",
    """DELETE FROM media_access_events WHERE asset_id IN $ASSETS OR source_id IN $SOURCES
         OR source_id IN (SELECT source_id FROM media_sources WHERE asset_id IN $ASSETS)""",
    "DELETE FROM asset_privacy_rules WHERE asset_id IN $ASSETS",
    "DELETE FROM restore_asset_bindings WHERE local_asset_id IN $ASSETS",
    "DELETE FROM media_sources WHERE source_id IN $SOURCES OR asset_id IN $ASSETS",
    "DELETE FROM media_assets WHERE asset_id IN $ASSETS",
)

/**
 * Removes every row of the files the user deleted for good from the trash (ADR-0018): tags, ratings,
 * albums, memos, edits, places, OCR and vectors. A file with another copy still on the device loses only
 * the deleted copy's source; the asset and the user's data stay with the copy.
 */
class RoomMediaPurge(private val db: IrohacoDatabase) : DeletedMediaStore {
    override suspend fun purge(contentUris: List<String>) {
        if (contentUris.isEmpty()) return
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TEMP TABLE IF NOT EXISTS purge_sources(source_id TEXT PRIMARY KEY)")
            sql.execSQL("CREATE TEMP TABLE IF NOT EXISTS purge_assets(asset_id TEXT PRIMARY KEY)")
            sql.execSQL("CREATE TEMP TABLE IF NOT EXISTS purge_discoveries(discovery_id TEXT PRIMARY KEY)")
            listOf("purge_sources", "purge_assets", "purge_discoveries").forEach { sql.execSQL("DELETE FROM $it") }
            // A detached source's URI may now be another file's, so only attached sources match.
            contentUris.chunked(500).forEach { uris ->
                sql.execSQL(
                    """INSERT OR IGNORE INTO purge_sources SELECT source_id FROM media_sources
                       WHERE media_store_id IS NOT NULL AND content_uri IN (${uris.joinToString { "?" }})""",
                    uris.toTypedArray(),
                )
            }
            sql.execSQL(
                """INSERT INTO purge_assets SELECT DISTINCT s.asset_id FROM media_sources s
                   WHERE s.source_id IN $SOURCES AND NOT EXISTS (
                     SELECT 1 FROM media_sources o WHERE o.asset_id = s.asset_id AND o.media_store_id IS NOT NULL AND o.source_id NOT IN $SOURCES)""",
            )
            sql.execSQL("INSERT INTO purge_discoveries SELECT discovery_id FROM identity_resolutions WHERE selected_asset_id IN $ASSETS")
            DELETES.forEach(sql::execSQL)
        }
    }
}
