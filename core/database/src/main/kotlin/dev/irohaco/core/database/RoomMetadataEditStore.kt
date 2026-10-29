package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.withTransaction
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.MetadataEditStore
import dev.irohaco.core.contracts.MetadataEdits
import dev.irohaco.core.contracts.MetadataField
import dev.irohaco.core.contracts.geoPointOfMetadataText
import dev.irohaco.core.contracts.toMetadataText
import dev.irohaco.core.domain.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.security.MessageDigest
import java.util.UUID

/** The EXIF tag names the edits use, so a later write-back (ADR-0030) finds them by name. */
private const val NAMESPACE = "exif"
private const val TAKEN_TAG = "DateTimeOriginal"
private const val DESCRIPTION_TAG = "ImageDescription"

// GPSLatitude and GPSLongitude together, as "latitude,longitude": one edit, one base, one conflict.
private const val LOCATION_TAG = "GPS"

private val MetadataField.tag
    get() = when (this) {
        MetadataField.TAKEN -> TAKEN_TAG
        MetadataField.LOCATION -> LOCATION_TAG
        MetadataField.DESCRIPTION -> DESCRIPTION_TAG
    }

/** The edited capture time of the asset `a`, for the timeline's order (MET-002). */
internal const val EDITED_TAKEN =
    "(SELECT v.int_value FROM metadata_override_sets o JOIN metadata_override_values v ON v.override_id = o.override_id " +
        "WHERE o.asset_id = a.asset_id AND o.namespace = '$NAMESPACE' AND o.tag_name = '$TAKEN_TAG')"

@Dao
interface MetadataDao {
    @Query(
        """SELECT o.tag_name AS tagName, o.edit_mode AS editMode, v.text_value AS textValue, v.int_value AS intValue
           FROM metadata_override_sets o LEFT JOIN metadata_override_values v ON v.override_id = o.override_id AND v.occurrence = 0
           WHERE o.asset_id = :assetId AND o.namespace = '$NAMESPACE'""",
    )
    fun observe(assetId: String): Flow<List<OverrideRow>>

    @Query(
        """SELECT o.tag_name AS tagName, b.text_value AS base FROM metadata_conflicts c
           JOIN metadata_override_sets o ON o.override_id = c.override_id
           LEFT JOIN metadata_observations b ON b.revision_id = c.base_revision_id AND b.namespace = o.namespace
             AND b.tag_name = o.tag_name AND b.occurrence = 0
           WHERE o.asset_id = :assetId AND o.namespace = '$NAMESPACE' AND c.resolved_at IS NULL""",
    )
    fun observeConflicts(assetId: String): Flow<List<ConflictRow>>

    @Query("SELECT override_id FROM metadata_override_sets WHERE asset_id = :assetId AND namespace = '$NAMESPACE' AND tag_name = :tag")
    suspend fun overrideId(assetId: String, tag: String): String?

    @Query("SELECT local_revision_no FROM metadata_override_sets WHERE override_id = :overrideId")
    suspend fun localRevision(overrideId: String): Int

    @Query("DELETE FROM metadata_override_values WHERE override_id = :overrideId")
    suspend fun deleteValues(overrideId: String)

    @Query("DELETE FROM metadata_override_sets WHERE override_id = :overrideId")
    suspend fun deleteSet(overrideId: String)

    @Query(
        """INSERT INTO metadata_override_sets(override_id, asset_id, namespace, tag_name, edit_mode, local_revision_no, modified_at)
           VALUES (:overrideId, :assetId, '$NAMESPACE', :tag, :mode, 1, :now)""",
    )
    suspend fun insertSet(overrideId: String, assetId: String, tag: String, mode: String, now: Long)

    @Query("UPDATE metadata_override_sets SET edit_mode = :mode, local_revision_no = local_revision_no + 1, modified_at = :now WHERE override_id = :overrideId")
    suspend fun updateSet(overrideId: String, mode: String, now: Long)

    @Query("INSERT INTO metadata_override_values(override_id, occurrence, text_value, int_value) VALUES (:overrideId, 0, :text, :number)")
    suspend fun insertValue(overrideId: String, text: String?, number: Long?)

    @Query("INSERT OR IGNORE INTO media_revisions(revision_id, asset_id, file_digest, observed_at) VALUES (:revisionId, :assetId, :digest, :now)")
    suspend fun insertRevision(revisionId: String, assetId: String, digest: String, now: Long): Long

    @Query("INSERT INTO metadata_observations(revision_id, namespace, tag_name, occurrence, text_value) VALUES (:revisionId, '$NAMESPACE', :tag, 0, :text)")
    suspend fun insertObservation(revisionId: String, tag: String, text: String)

    @Query("SELECT text_value FROM metadata_observations WHERE revision_id = :revisionId AND namespace = '$NAMESPACE' AND tag_name = :tag AND occurrence = 0")
    suspend fun observation(revisionId: String, tag: String): String?

    @Query("SELECT base_revision_id FROM metadata_sync_baselines WHERE override_id = :overrideId")
    suspend fun baseRevision(overrideId: String): String?

    @Query(
        """INSERT OR REPLACE INTO metadata_sync_baselines(override_id, base_revision_id, base_local_revision_no, checked_at)
           VALUES (:overrideId, :revisionId, (SELECT local_revision_no FROM metadata_override_sets WHERE override_id = :overrideId), :now)""",
    )
    suspend fun setBase(overrideId: String, revisionId: String, now: Long)

    @Query("DELETE FROM metadata_sync_baselines WHERE override_id = :overrideId")
    suspend fun deleteBase(overrideId: String)

    @Query(
        """INSERT OR IGNORE INTO metadata_conflicts(conflict_id, override_id, base_revision_id, remote_revision_id, observed_local_revision_no, detected_at)
           SELECT :overrideId || ':' || :remoteRevisionId || ':' || local_revision_no, :overrideId, :baseRevisionId, :remoteRevisionId, local_revision_no, :now
           FROM metadata_override_sets WHERE override_id = :overrideId""",
    )
    suspend fun insertConflict(overrideId: String, baseRevisionId: String, remoteRevisionId: String, now: Long)

    @Query("SELECT remote_revision_id FROM metadata_conflicts WHERE override_id = :overrideId AND resolved_at IS NULL ORDER BY detected_at DESC, rowid DESC LIMIT 1")
    suspend fun openConflictRemote(overrideId: String): String?

    @Query("UPDATE metadata_conflicts SET resolution_code = :resolution, resolved_at = :now WHERE override_id = :overrideId AND resolved_at IS NULL")
    suspend fun resolveConflicts(overrideId: String, resolution: String, now: Long)

    @Query("DELETE FROM metadata_conflicts WHERE override_id = :overrideId")
    suspend fun deleteConflicts(overrideId: String)

    /** Replaces the tag's value, or marks it removed when both [text] and [number] are null (UC-META-01). */
    @Transaction
    suspend fun set(newId: String, assetId: String, tag: String, text: String?, number: Long?, now: Long) {
        val remove = text == null && number == null
        val mode = if (remove) "REMOVE" else "REPLACE"
        // Values go first: the schema refuses REMOVE while values remain.
        val id = overrideId(assetId, tag)?.also { deleteValues(it); updateSet(it, mode, now) }
            ?: newId.also { insertSet(it, assetId, tag, mode, now) }
        if (!remove) insertValue(id, text, number)
        // Editing a conflicting field again merges it by hand against the file's newer value.
        openConflictRemote(id)?.let { remote ->
            resolveConflicts(id, "MANUAL_MERGE", now)
            setBase(id, remote, now)
        }
    }

    @Transaction
    suspend fun clear(assetId: String, tag: String) {
        overrideId(assetId, tag)?.let { deleteConflicts(it); deleteBase(it); deleteValues(it); deleteSet(it) }
    }

    @Transaction
    suspend fun keep(assetId: String, tag: String, now: Long) {
        val id = overrideId(assetId, tag) ?: return
        val remote = openConflictRemote(id) ?: return
        resolveConflicts(id, "KEEP_LOCAL", now)
        setBase(id, remote, now)
    }

    /**
     * The file's values become one revision, named by their digest so the same values are the same revision.
     * Only fields read from the file are in it, so a base always holds its field, even with no value.
     */
    @Transaction
    suspend fun compare(assetId: String, file: Map<String, String?>, edited: Map<String, String?>, now: Long) {
        val sorted = file.toSortedMap()
        val digest = MessageDigest.getInstance("SHA-256").digest(sorted.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val revision = "meta:$assetId:$digest"
        if (insertRevision(revision, assetId, digest, now) != -1L) {
            sorted.forEach { (tag, text) -> if (text != null) insertObservation(revision, tag, text) }
        }
        for ((tag, remote) in sorted) {
            val id = overrideId(assetId, tag) ?: continue
            val base = baseRevision(id)
            when {
                base == null -> setBase(id, revision, now)
                base == revision || remote == observation(base, tag) -> Unit
                remote == edited[tag] -> {
                    resolveConflicts(id, "KEEP_LOCAL", now)
                    setBase(id, revision, now)
                }
                else -> insertConflict(id, base, revision, now)
            }
        }
    }
}

data class OverrideRow(val tagName: String, val editMode: String, val textValue: String?, val intValue: Long?)

data class ConflictRow(val tagName: String, val base: String?)

/**
 * Edits in the override tables, with the file's values they were made against (ADR-0007). The location is
 * also a USER row of `location_evidence`, which the map and the place search read.
 */
class RoomMetadataEditStore(
    private val db: IrohacoDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : MetadataEditStore {
    private val dao get() = db.metadataDao()

    override fun edits(assetId: String): Flow<MetadataEdits> =
        combine(dao.observe(assetId), dao.observeConflicts(assetId)) { rows, conflicts ->
            val byTag = rows.associateBy { it.tagName }
            MetadataEdits(
                takenAtMs = byTag[TAKEN_TAG]?.intValue,
                location = byTag[LOCATION_TAG]?.text()?.map(::geoPointOfMetadataText),
                description = byTag[DESCRIPTION_TAG]?.text(),
                conflicts = conflicts.mapNotNull { row -> MetadataField.entries.find { it.tag == row.tagName }?.let { it to row.base } }.toMap(),
            )
        }

    private fun OverrideRow.text(): FieldEdit<String> = if (editMode == "REMOVE") FieldEdit.Removed else FieldEdit.Set(textValue.orEmpty())

    private fun <T, R> FieldEdit<T>.map(f: (T) -> R): FieldEdit<R> = when (this) {
        FieldEdit.Removed -> FieldEdit.Removed
        is FieldEdit.Set -> FieldEdit.Set(f(value))
    }

    override suspend fun setTakenAt(assetId: String, takenAtMs: Long?) =
        if (takenAtMs == null) dao.clear(assetId, TAKEN_TAG) else dao.set(newId(), assetId, TAKEN_TAG, null, takenAtMs, now())

    override suspend fun setLocation(assetId: String, edit: FieldEdit<GeoPoint>?) = db.withTransaction {
        db.locationDao().setUser(assetId, edit)
        setText(assetId, LOCATION_TAG, edit?.map { it.toMetadataText() })
    }

    override suspend fun setDescription(assetId: String, edit: FieldEdit<String>?) = setText(assetId, DESCRIPTION_TAG, edit)

    private suspend fun setText(assetId: String, tag: String, edit: FieldEdit<String>?) = when (edit) {
        null -> dao.clear(assetId, tag)
        FieldEdit.Removed -> dao.set(newId(), assetId, tag, null, null, now())
        is FieldEdit.Set -> dao.set(newId(), assetId, tag, edit.value, null, now())
    }

    override suspend fun compareWithFile(assetId: String, file: Map<MetadataField, String?>, edited: Map<MetadataField, String?>) =
        dao.compare(assetId, file.mapKeys { it.key.tag }, edited.mapKeys { it.key.tag }, now())

    override suspend fun keepEdit(assetId: String, field: MetadataField) = dao.keep(assetId, field.tag, now())
}
