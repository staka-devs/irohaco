package dev.irohaco.core.database

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.irohaco.core.contracts.TextIndexStore
import dev.irohaco.core.contracts.TextMatch
import java.text.Normalizer

/**
 * Japanese has no spaces between words, so the full-text index keeps characters and pairs of
 * characters (N-grams) as its terms (DATABASE_DESIGN §9). A query of two or more characters matches
 * as a phrase of consecutive pairs, which is a substring match; a single character matches alone.
 */
internal object TextGrams {
    /** Characters, then pairs of neighbours within each run of letters and digits. */
    fun terms(text: String): String {
        val runs = runs(text)
        val singles = runs.flatten()
        val pairs = runs.flatMap { run -> run.zipWithNext { a, b -> a + b } }
        return (singles + pairs).joinToString(" ")
    }

    /** An FTS MATCH expression that finds [text] as a substring, or null when it has no letters or digits. */
    fun query(text: String): String? = runs(text)
        .map { run -> if (run.size == 1) run[0] else run.zipWithNext { a, b -> a + b }.joinToString(" ", "\"", "\"") }
        .ifEmpty { return null }
        .joinToString(" ")

    /** Runs of letters and digits, as code points, after width and case folding. */
    private fun runs(text: String): List<List<String>> =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
            .split(SEPARATORS)
            .filter { it.isNotEmpty() }
            .map { run -> run.codePoints().toArray().map { String(Character.toChars(it)) } }

    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
}

/**
 * OCR text in `ocr_observations`, and `ocr_fts`, a derived FTS4 table over it whose rows share the
 * observation's rowid. FTS4 with the simple tokenizer is in every Android SQLite; the table is not a
 * Room entity, so it is made when the database opens and written with plain SQL (M2-07).
 */
class RoomTextIndexStore(private val db: IrohacoDatabase) : TextIndexStore {
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    override suspend fun recordOcr(targetId: String, revisionDigest: String, text: String, engineRevision: String) {
        val column = when {
            targetId.startsWith("asset:") -> "asset_id"
            targetId.startsWith("frame:") -> "frame_id"
            else -> return
        }
        val subject = targetId.substringAfter(':')
        val ocrId = "ocr:$targetId:$revisionDigest"
        db.withTransaction {
            // Text an older revision left goes first, from the index too.
            sql.execSQL("DELETE FROM ocr_fts WHERE docid IN (SELECT rowid FROM ocr_observations WHERE $column = ? AND ocr_id <> ?)", arrayOf(subject, ocrId))
            sql.execSQL("DELETE FROM ocr_observations WHERE $column = ? AND ocr_id <> ?", arrayOf(subject, ocrId))
            if (text.isBlank()) return@withTransaction
            sql.execSQL(
                "INSERT OR IGNORE INTO ocr_observations(ocr_id, $column, text, engine_revision) VALUES (?, ?, ?, ?)",
                arrayOf(ocrId, subject, text, engineRevision),
            )
            sql.execSQL(
                "INSERT INTO ocr_fts(docid, terms) SELECT rowid, ? FROM ocr_observations o WHERE ocr_id = ? AND NOT EXISTS (SELECT 1 FROM ocr_fts WHERE docid = o.rowid)",
                arrayOf(TextGrams.terms(text), ocrId),
            )
        }
    }

    override suspend fun search(query: String, limit: Int): List<TextMatch> {
        val match = TextGrams.query(query) ?: return emptyList()
        return sql.query(
            """SELECT a.asset_id, f.timestamp_ms, o.text
               FROM ocr_fts JOIN ocr_observations o ON o.rowid = ocr_fts.docid
                 LEFT JOIN sampled_frames f ON f.frame_id = o.frame_id
                 LEFT JOIN video_segments g ON g.segment_id = f.segment_id
                 LEFT JOIN media_revisions r ON r.revision_id = g.revision_id
                 JOIN media_assets a ON a.asset_id = COALESCE(o.asset_id, r.asset_id)
               WHERE ocr_fts MATCH ? AND a.tombstoned_at IS NULL
                 AND EXISTS (SELECT 1 FROM media_sources s WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE')
               ORDER BY a.asset_id, f.timestamp_ms LIMIT ?""",
            arrayOf<Any>(match, limit),
        ).use { c ->
            buildList { while (c.moveToNext()) add(TextMatch(c.getString(0), if (c.isNull(1)) null else c.getLong(1), c.getString(2))) }
        } + memos(match, limit)
    }

    /** Searchable memos whose current text matches (ANN-002); `annotation_fts` holds only those. */
    private fun memos(match: String, limit: Int): List<TextMatch> = sql.query(
        """SELECT a.asset_id, r.text
           FROM annotation_fts JOIN semantic_annotations n ON n.rowid = annotation_fts.docid
             JOIN annotation_subjects sub ON sub.subject_id = n.subject_id
             JOIN media_assets a ON a.asset_id = sub.asset_id
             JOIN semantic_annotation_revisions r ON r.annotation_id = n.annotation_id
               AND r.revision_no = (SELECT MAX(revision_no) FROM semantic_annotation_revisions WHERE annotation_id = n.annotation_id)
           WHERE annotation_fts MATCH ? AND n.searchable = 1 AND a.tombstoned_at IS NULL
             AND EXISTS (SELECT 1 FROM media_sources s WHERE s.asset_id = a.asset_id AND s.access_state = 'AVAILABLE')
           ORDER BY a.asset_id LIMIT ?""",
        arrayOf<Any>(match, limit),
    ).use { c -> buildList { while (c.moveToNext()) add(TextMatch(c.getString(0), null, c.getString(1), fromMemo = true)) } }

    internal companion object {
        /** Run on every open: the table is derived and can be dropped and rebuilt from ocr_observations. */
        const val CREATE_FTS = "CREATE VIRTUAL TABLE IF NOT EXISTS ocr_fts USING fts4(terms, tokenize=simple)"
    }
}
