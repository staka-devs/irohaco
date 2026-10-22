package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.withTransaction
import dev.irohaco.core.contracts.Annotation
import dev.irohaco.core.contracts.AnnotationAuthor
import dev.irohaco.core.contracts.AnnotationKind
import dev.irohaco.core.contracts.AnnotationRevision
import dev.irohaco.core.contracts.AnnotationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A memo's current revision as a vector's revision digest; `n` is the memo's `semantic_annotations` row. */
internal const val MEMO_REVISION =
    "(SELECT 'rev:' || MAX(mr.revision_no) FROM semantic_annotation_revisions mr WHERE mr.annotation_id = n.annotation_id)"

private const val CURRENT =
    """JOIN semantic_annotation_revisions r ON r.annotation_id = n.annotation_id
         AND r.revision_no = (SELECT MAX(revision_no) FROM semantic_annotation_revisions WHERE annotation_id = n.annotation_id)"""

@Dao
interface AnnotationDao {
    @Query("INSERT OR IGNORE INTO annotation_subjects(subject_id, asset_id) VALUES ('asset:' || :assetId, :assetId)")
    suspend fun insertSubject(assetId: String)

    @Query(
        """INSERT INTO semantic_annotations(annotation_id, subject_id, annotation_kind, searchable, exportable, created_at)
           VALUES (:annotationId, (SELECT subject_id FROM annotation_subjects WHERE asset_id = :assetId), :kind, 1, 1, :createdAt)""",
    )
    suspend fun insertAnnotation(annotationId: String, assetId: String, kind: String, createdAt: Long)

    /** The shortest indexed scene of [assetId]'s current revision that holds [atMs]. */
    @Query(
        """SELECT g.segment_id FROM video_segments g JOIN media_revisions mrv ON mrv.revision_id = g.revision_id
             JOIN media_assets a ON a.asset_id = mrv.asset_id
           WHERE a.asset_id = :assetId AND mrv.file_digest = $REVISION AND :atMs >= g.start_ms AND :atMs < g.end_ms
           ORDER BY g.end_ms - g.start_ms LIMIT 1""",
    )
    suspend fun sceneAt(assetId: String, atMs: Long): String?

    @Query("INSERT OR IGNORE INTO annotation_subjects(subject_id, segment_id) VALUES ('scene:' || :segmentId, :segmentId)")
    suspend fun insertSceneSubject(segmentId: String)

    @Query(
        """INSERT INTO semantic_annotations(annotation_id, subject_id, annotation_kind, searchable, exportable, created_at)
           VALUES (:annotationId, (SELECT subject_id FROM annotation_subjects WHERE segment_id = :segmentId), :kind, 1, 1, :createdAt)""",
    )
    suspend fun insertSceneAnnotation(annotationId: String, segmentId: String, kind: String, createdAt: Long)

    @Query(
        """INSERT INTO semantic_annotation_revisions(annotation_id, revision_no, author, model_profile_id, text, modified_at)
           VALUES (:annotationId, (SELECT COALESCE(MAX(revision_no), 0) + 1 FROM semantic_annotation_revisions WHERE annotation_id = :annotationId),
                   :author, :modelProfileId, :text, :modifiedAt)""",
    )
    suspend fun insertRevision(annotationId: String, author: String, modelProfileId: String?, text: String, modifiedAt: Long)

    @Query(
        """SELECT n.annotation_id AS annotationId, n.annotation_kind AS kind, n.searchable AS searchable, n.created_at AS createdAt,
             r.revision_no AS revisionNo, r.author AS author, r.model_profile_id AS modelProfileId, r.text AS text, r.modified_at AS modifiedAt,
             sg.start_ms AS sceneStartMs, sg.end_ms AS sceneEndMs
           FROM semantic_annotations n $MEMO_FILE $CURRENT
           WHERE a.asset_id = :assetId ORDER BY sg.start_ms IS NOT NULL, sg.start_ms, n.created_at, n.annotation_id""",
    )
    fun annotations(assetId: String): Flow<List<AnnotationRow>>

    @Query(
        """SELECT revision_no AS revisionNo, author, model_profile_id AS modelProfileId, text, modified_at AS modifiedAt
           FROM semantic_annotation_revisions WHERE annotation_id = :annotationId ORDER BY revision_no DESC""",
    )
    suspend fun history(annotationId: String): List<RevisionRow>

    @Query("UPDATE semantic_annotations SET searchable = :searchable WHERE annotation_id = :annotationId")
    suspend fun setSearchable(annotationId: String, searchable: Boolean)

    @Query("DELETE FROM embedding_vectors WHERE target_id IN (SELECT target_id FROM embedding_targets WHERE annotation_id = :annotationId)")
    suspend fun deleteVectors(annotationId: String)

    @Query("DELETE FROM indexing_job_items WHERE target_id IN (SELECT target_id FROM embedding_targets WHERE annotation_id = :annotationId)")
    suspend fun deleteJobItems(annotationId: String)

    @Query("DELETE FROM embedding_targets WHERE annotation_id = :annotationId")
    suspend fun deleteTargets(annotationId: String)

    @Query("DELETE FROM semantic_annotation_revisions WHERE annotation_id = :annotationId")
    suspend fun deleteRevisions(annotationId: String)

    @Query("DELETE FROM semantic_annotations WHERE annotation_id = :annotationId")
    suspend fun deleteAnnotation(annotationId: String)
}

data class AnnotationRow(
    val annotationId: String,
    val kind: String,
    val searchable: Boolean,
    val createdAt: Long,
    val revisionNo: Int,
    val author: String,
    val modelProfileId: String?,
    val text: String,
    val modifiedAt: Long,
    val sceneStartMs: Long?,
    val sceneEndMs: Long?,
)

data class RevisionRow(val revisionNo: Int, val author: String, val modelProfileId: String?, val text: String, val modifiedAt: Long)

/**
 * Search memos (M2-11). `annotation_fts` holds the searchable memos' current text as N-grams, under the
 * memo's rowid; like `ocr_fts` it is derived, made when the database opens and written with plain SQL.
 */
class RoomAnnotationStore(private val db: IrohacoDatabase) : AnnotationStore {
    private val dao get() = db.annotationDao()

    override fun annotations(assetId: String): Flow<List<Annotation>> = dao.annotations(assetId).map { rows ->
        rows.map {
            Annotation(
                it.annotationId,
                AnnotationKind.valueOf(it.kind),
                it.searchable,
                it.createdAt,
                AnnotationRevision(it.revisionNo, AnnotationAuthor.valueOf(it.author), it.modelProfileId, it.text, it.modifiedAt),
                it.sceneStartMs?.let { start -> it.sceneEndMs?.let { end -> start until end } },
            )
        }
    }

    override suspend fun create(annotationId: String, assetId: String, kind: AnnotationKind, text: String, author: AnnotationAuthor, modelProfileId: String?, now: Long) =
        db.withTransaction {
            dao.insertSubject(assetId)
            dao.insertAnnotation(annotationId, assetId, kind.name, now)
            dao.insertRevision(annotationId, author.name, modelProfileId, text, now)
            refreshFts(annotationId)
        }

    override suspend fun createOnScene(
        annotationId: String,
        assetId: String,
        atMs: Long,
        kind: AnnotationKind,
        text: String,
        author: AnnotationAuthor,
        modelProfileId: String?,
        now: Long,
    ): Boolean = db.withTransaction {
        val segmentId = dao.sceneAt(assetId, atMs) ?: return@withTransaction false
        dao.insertSceneSubject(segmentId)
        dao.insertSceneAnnotation(annotationId, segmentId, kind.name, now)
        dao.insertRevision(annotationId, author.name, modelProfileId, text, now)
        refreshFts(annotationId)
        true
    }

    override suspend fun revise(annotationId: String, text: String, author: AnnotationAuthor, modelProfileId: String?, now: Long) =
        db.withTransaction {
            dao.insertRevision(annotationId, author.name, modelProfileId, text, now)
            refreshFts(annotationId)
        }

    override suspend fun setSearchable(annotationId: String, searchable: Boolean) = db.withTransaction {
        dao.setSearchable(annotationId, searchable)
        refreshFts(annotationId)
    }

    override suspend fun history(annotationId: String) =
        dao.history(annotationId).map { AnnotationRevision(it.revisionNo, AnnotationAuthor.valueOf(it.author), it.modelProfileId, it.text, it.modifiedAt) }

    override suspend fun delete(annotationId: String) = db.withTransaction {
        sql.execSQL("DELETE FROM annotation_fts WHERE docid = (SELECT rowid FROM semantic_annotations WHERE annotation_id = ?)", arrayOf(annotationId))
        dao.deleteVectors(annotationId)
        dao.deleteJobItems(annotationId)
        dao.deleteTargets(annotationId)
        dao.deleteRevisions(annotationId)
        dao.deleteAnnotation(annotationId)
    }

    private val sql get() = db.openHelper.writableDatabase

    /** The index holds the current text of a searchable memo, and nothing of one left out of search. */
    private fun refreshFts(annotationId: String) {
        sql.execSQL("DELETE FROM annotation_fts WHERE docid = (SELECT rowid FROM semantic_annotations WHERE annotation_id = ?)", arrayOf(annotationId))
        sql.query("SELECT n.rowid, r.text FROM semantic_annotations n $CURRENT WHERE n.annotation_id = ? AND n.searchable = 1", arrayOf(annotationId)).use { c ->
            if (c.moveToFirst()) sql.execSQL("INSERT INTO annotation_fts(docid, terms) VALUES (?, ?)", arrayOf<Any>(c.getLong(0), TextGrams.terms(c.getString(1))))
        }
    }

    internal companion object {
        const val CREATE_FTS = "CREATE VIRTUAL TABLE IF NOT EXISTS annotation_fts USING fts4(terms, tokenize=simple)"
    }
}

/**
 * A memo's file, `a`: the photo or video it is on, or the video whose scene `sg` it is on (M2-11, #155).
 * `n` is the memo's `semantic_annotations` row.
 */
internal const val MEMO_FILE =
    """JOIN annotation_subjects sub ON sub.subject_id = n.subject_id
       LEFT JOIN video_segments sg ON sg.segment_id = sub.segment_id
       LEFT JOIN media_revisions sr ON sr.revision_id = sg.revision_id
       JOIN media_assets a ON a.asset_id = COALESCE(sub.asset_id, sr.asset_id)"""
