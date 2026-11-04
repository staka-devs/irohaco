package dev.irohaco.core.database

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.withTransaction
import dev.irohaco.core.contracts.BackupManifest
import dev.irohaco.core.contracts.BackupRow
import dev.irohaco.core.contracts.BackupStore
import dev.irohaco.core.contracts.BackupTables

@Dao
interface BackupDao {
    @Query(
        """INSERT INTO backup_archives(backup_id, format_version, created_at, archive_ref, manifest_sha256, encryption_code, state)
           VALUES (:backupId, :formatVersion, :createdAt, :archiveRef, '', 'AES_256_GCM', 'STAGING')""",
    )
    suspend fun begin(backupId: String, formatVersion: Int, createdAt: Long, archiveRef: String)

    @Query("UPDATE backup_archives SET state = 'VERIFIED', manifest_sha256 = :manifestSha256 WHERE backup_id = :backupId AND state = 'STAGING'")
    suspend fun verified(backupId: String, manifestSha256: String)

    @Query("UPDATE backup_archives SET state = 'FAILED' WHERE backup_id = :backupId AND state = 'STAGING'")
    suspend fun failed(backupId: String)

    @Query("INSERT INTO backup_archive_entries(backup_id, entity_kind, exported_entity_id, content_sha256) VALUES (:backupId, :kind, :key, :sha256)")
    suspend fun entry(backupId: String, kind: String, key: String, sha256: String)

    @Transaction
    suspend fun finish(backupId: String, manifest: BackupManifest) {
        for ((kind, rows) in manifest.entries) for ((key, sha256) in rows) entry(backupId, kind, key, sha256)
        verified(backupId, manifest.sha256)
    }
}

/**
 * The user's data as UC-BACKUP-01 exports it: what the user made or decided, keyed by the ids the
 * restore maps from, and for each file it touches what it can be found again by. Observations,
 * vectors, jobs, keys and grants stay behind; they come back by syncing and indexing again.
 */
class RoomBackupStore(private val db: IrohacoDatabase) : BackupStore {
    private val dao = db.backupDao()

    override suspend fun snapshot(): BackupTables = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        KINDS.mapValues { (_, query) -> sql.query(query).use(::rows) }
    }

    override suspend fun begin(backupId: String, archiveRef: String, createdAt: Long) = dao.begin(backupId, FORMAT_VERSION, createdAt, archiveRef)

    override suspend fun finish(backupId: String, manifest: BackupManifest?) {
        if (manifest == null) dao.failed(backupId) else dao.finish(backupId, manifest)
    }

    private fun rows(cursor: Cursor): List<BackupRow> {
        val names = cursor.columnNames
        val keyAt = names.indexOf("key")
        return buildList {
            while (cursor.moveToNext()) {
                val values = linkedMapOf<String, Any?>()
                for (i in names.indices) {
                    if (i == keyAt) continue
                    values[names[i]] = when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                        else -> cursor.getString(i)
                    }
                }
                add(BackupRow(cursor.getString(keyAt), values))
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1

        /** Files the exported records point at. */
        const val USED_ASSETS = """
            SELECT asset_id FROM media_tags UNION SELECT asset_id FROM media_ratings UNION SELECT asset_id FROM album_items
            UNION SELECT asset_id FROM annotation_subjects WHERE asset_id IS NOT NULL
            UNION SELECT r.asset_id FROM annotation_subjects sub JOIN video_segments g ON g.segment_id = sub.segment_id
              JOIN media_revisions r ON r.revision_id = g.revision_id
            UNION SELECT asset_id FROM location_evidence UNION SELECT supporting_asset_id FROM location_evidence_support
            UNION SELECT asset_id FROM metadata_override_sets UNION SELECT asset_id FROM asset_privacy_rules"""

        /** Memos with something the user wrote; text an AI wrote is made again rather than restored. */
        const val USER_MEMOS = "SELECT annotation_id FROM semantic_annotation_revisions WHERE author = 'USER'"

        /** Each kind and the query that reads it; `key` is the record's id within its kind. */
        val KINDS = linkedMapOf(
            // Name, folder, size and capture time find the file again on another phone (AC-011); the content URI does not.
            "assets" to """SELECT a.asset_id AS key, a.asset_id, a.media_kind, a.tombstoned_at, s.display_name, s.relative_path, s.mime_type,
                  s.file_size_bytes, s.observed_taken_at, s.observed_duration_ms, r.width_px, r.height_px
                FROM media_assets a
                LEFT JOIN media_sources s ON s.source_id = (SELECT MIN(source_id) FROM media_sources WHERE asset_id = a.asset_id)
                LEFT JOIN asset_current_revisions c ON c.asset_id = a.asset_id
                LEFT JOIN media_revisions r ON r.revision_id = c.revision_id
                WHERE a.asset_id IN ($USED_ASSETS)""",
            "tags" to "SELECT tag_id AS key, * FROM tags",
            "media_tags" to "SELECT asset_id || '|' || tag_id AS key, * FROM media_tags",
            "media_ratings" to "SELECT asset_id AS key, * FROM media_ratings",
            "albums" to "SELECT album_id AS key, * FROM albums",
            "album_items" to "SELECT album_id || '|' || asset_id AS key, * FROM album_items",
            "video_segments" to """SELECT g.segment_id AS key, g.segment_id, r.asset_id, g.start_ms, g.end_ms FROM video_segments g
                JOIN media_revisions r ON r.revision_id = g.revision_id
                WHERE g.segment_id IN (SELECT segment_id FROM annotation_subjects)""",
            "annotation_subjects" to "SELECT subject_id AS key, * FROM annotation_subjects WHERE subject_id IN (SELECT subject_id FROM semantic_annotations WHERE annotation_id IN ($USER_MEMOS))",
            "semantic_annotations" to "SELECT annotation_id AS key, * FROM semantic_annotations WHERE annotation_id IN ($USER_MEMOS)",
            "semantic_annotation_revisions" to
                """SELECT annotation_id || '|' || revision_no AS key, annotation_id, revision_no, author, text, modified_at
                FROM semantic_annotation_revisions WHERE author = 'USER'""",
            "location_evidence" to "SELECT evidence_id AS key, * FROM location_evidence",
            "location_evidence_support" to "SELECT evidence_id || '|' || supporting_asset_id AS key, * FROM location_evidence_support",
            "metadata_override_sets" to "SELECT override_id AS key, * FROM metadata_override_sets",
            "metadata_override_values" to "SELECT override_id || '|' || occurrence AS key, * FROM metadata_override_values",
            "asset_privacy_rules" to "SELECT asset_id AS key, * FROM asset_privacy_rules",
            "saved_searches" to "SELECT saved_search_id AS key, saved_search_id, name FROM saved_searches",
            "search_rule_nodes" to "SELECT rule_node_id AS key, * FROM search_rule_nodes",
            "saved_search_roots" to "SELECT saved_search_id AS key, * FROM saved_search_roots",
            "search_rule_set_values" to "SELECT rule_node_id || '|' || value AS key, * FROM search_rule_set_values",
            "smart_album_rules" to "SELECT album_id AS key, * FROM smart_album_rules",
        )
    }
}
