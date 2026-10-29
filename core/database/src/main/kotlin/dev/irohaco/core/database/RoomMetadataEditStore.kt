package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.MetadataEditStore
import dev.irohaco.core.contracts.MetadataEdits
import dev.irohaco.core.domain.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/** The EXIF tag names the edits use, so a later write-back (ADR-0030) finds them by name. */
private const val NAMESPACE = "exif"
private const val TAKEN_TAG = "DateTimeOriginal"
private const val DESCRIPTION_TAG = "ImageDescription"

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

    @Query("SELECT override_id FROM metadata_override_sets WHERE asset_id = :assetId AND namespace = '$NAMESPACE' AND tag_name = :tag")
    suspend fun overrideId(assetId: String, tag: String): String?

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

    /** Replaces the tag's value, or marks it removed when both [text] and [number] are null (UC-META-01). */
    @Transaction
    suspend fun set(newId: String, assetId: String, tag: String, text: String?, number: Long?, now: Long) {
        val remove = text == null && number == null
        val mode = if (remove) "REMOVE" else "REPLACE"
        // Values go first: the schema refuses REMOVE while values remain.
        val id = overrideId(assetId, tag)?.also { deleteValues(it); updateSet(it, mode, now) }
            ?: newId.also { insertSet(it, assetId, tag, mode, now) }
        if (!remove) insertValue(id, text, number)
    }

    @Transaction
    suspend fun clear(assetId: String, tag: String) {
        overrideId(assetId, tag)?.let { deleteValues(it); deleteSet(it) }
    }
}

data class OverrideRow(val tagName: String, val editMode: String, val textValue: String?, val intValue: Long?)

data class UserLocationRow(val status: String, val latitude: Double?, val longitude: Double?)

/** Capture time and description in the override tables, the location as a USER row of `location_evidence`. */
class RoomMetadataEditStore(
    private val db: IrohacoDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : MetadataEditStore {
    private val dao get() = db.metadataDao()

    override fun edits(assetId: String): Flow<MetadataEdits> =
        combine(dao.observe(assetId), db.locationDao().observeUser(assetId)) { rows, location ->
            val byTag = rows.associateBy { it.tagName }
            MetadataEdits(
                takenAtMs = byTag[TAKEN_TAG]?.intValue,
                location = location?.let { if (it.latitude != null && it.longitude != null) FieldEdit.Set(GeoPoint(it.latitude, it.longitude)) else FieldEdit.Removed },
                description = byTag[DESCRIPTION_TAG]?.let { if (it.editMode == "REMOVE") FieldEdit.Removed else FieldEdit.Set(it.textValue.orEmpty()) },
            )
        }

    override suspend fun setTakenAt(assetId: String, takenAtMs: Long?) =
        if (takenAtMs == null) dao.clear(assetId, TAKEN_TAG) else dao.set(newId(), assetId, TAKEN_TAG, null, takenAtMs, now())

    override suspend fun setLocation(assetId: String, edit: FieldEdit<GeoPoint>?) =
        db.locationDao().setUser(assetId, edit)

    override suspend fun setDescription(assetId: String, edit: FieldEdit<String>?) = when (edit) {
        null -> dao.clear(assetId, DESCRIPTION_TAG)
        FieldEdit.Removed -> dao.set(newId(), assetId, DESCRIPTION_TAG, null, null, now())
        is FieldEdit.Set -> dao.set(newId(), assetId, DESCRIPTION_TAG, edit.value, null, now())
    }
}
