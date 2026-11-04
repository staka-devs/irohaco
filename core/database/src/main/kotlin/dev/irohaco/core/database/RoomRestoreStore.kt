package dev.irohaco.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.irohaco.core.contracts.BackupContents
import dev.irohaco.core.contracts.BackupRow
import dev.irohaco.core.contracts.RestoreMatches
import dev.irohaco.core.contracts.RestoreResult
import dev.irohaco.core.contracts.RestoreStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Restores a backup into this database (UC-RESTORE-01, AC-011). Files are matched by name, size and
 * capture time, never by content URI; only a single match is used. Records are added, never written
 * over what is here, so an edit made on this device stays as it is.
 */
class RoomRestoreStore(private val db: IrohacoDatabase) : RestoreStore {
    private val sql get() = db.openHelper.writableDatabase

    override suspend fun stage(restoreId: String, source: String, contents: BackupContents, startedAt: Long): RestoreMatches = db.withTransaction {
        sql.execSQL(
            """INSERT OR IGNORE INTO backup_archives(backup_id, format_version, created_at, archive_ref, manifest_sha256, encryption_code, state)
               VALUES (?, ?, ?, ?, ?, 'AES_256_GCM', 'VERIFIED')""",
            arrayOf<Any?>(contents.backupId, contents.formatVersion, contents.createdAt, source, contents.manifest.sha256),
        )
        sql.execSQL("INSERT INTO restore_sessions(restore_id, backup_id, restore_state, started_at) VALUES (?, ?, 'STAGING', ?)", arrayOf<Any?>(restoreId, contents.backupId, startedAt))
        var matched = 0
        var review = 0
        var missing = 0
        for (asset in contents.tables["assets"].orEmpty()) {
            val v = asset.values
            val candidates = if (v["display_name"] == null) {
                emptyList()
            } else {
                strings(
                    """SELECT DISTINCT a.asset_id FROM media_assets a JOIN media_sources s ON s.asset_id = a.asset_id
                       WHERE a.tombstoned_at IS NULL AND s.display_name = ? AND s.file_size_bytes = ? AND s.observed_taken_at IS ?""",
                    v["display_name"], v["file_size_bytes"], v["observed_taken_at"],
                )
            }
            // The same file on the same phone keeps its id; elsewhere only a single match counts.
            val local = if (asset.key in candidates) asset.key else candidates.singleOrNull()
            val decision = when {
                local != null -> "MATCHED".also { matched++ }
                candidates.isEmpty() -> "SKIPPED".also { missing++ }
                else -> "NEEDS_REVIEW".also { review++ }
            }
            sql.execSQL(
                "INSERT INTO restore_asset_bindings(restore_id, exported_asset_id, local_asset_id, match_decision) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(restoreId, asset.key, local, decision),
            )
        }
        state(restoreId, "PREVIEW")
        RestoreMatches(matched, review, missing)
    }

    override suspend fun apply(restoreId: String, contents: BackupContents, completedAt: Long): RestoreResult = try {
        db.withTransaction {
            state(restoreId, "APPLYING")
            val assets = strings("SELECT exported_asset_id || '|' || local_asset_id FROM restore_asset_bindings WHERE restore_id = ? AND match_decision = 'MATCHED'", restoreId)
                .associate { it.substringBefore('|') to it.substringAfter('|') }
            val result = Applier(contents, assets).run()
            state(restoreId, "VERIFYING")
            val broken = sql.query("PRAGMA foreign_key_check").use { it.count }
            check(broken == 0) { "$broken restored records point at nothing" }
            sql.execSQL("UPDATE restore_sessions SET restore_state = 'COMMITTED', completed_at = ? WHERE restore_id = ?", arrayOf<Any?>(completedAt, restoreId))
            result
        }
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            sql.execSQL("UPDATE restore_sessions SET restore_state = 'ROLLED_BACK', completed_at = ? WHERE restore_id = ?", arrayOf<Any?>(completedAt, restoreId))
        }
        throw e
    }

    private fun state(restoreId: String, state: String) =
        sql.execSQL("UPDATE restore_sessions SET restore_state = ? WHERE restore_id = ?", arrayOf<Any?>(state, restoreId))

    private fun strings(query: String, vararg args: Any?): List<String> = sql.query(SimpleSQLiteQuery(query, args)).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    private fun one(query: String, vararg args: Any?): String? = strings(query, *args).firstOrNull()

    private fun exists(table: String, column: String, value: Any?) = one("SELECT 1 FROM $table WHERE $column = ?", value) != null

    /** One restore's records into the tables, each id mapped to what it is on this device. */
    private inner class Applier(contents: BackupContents, private val assets: Map<String, String>) {
        private val tables = contents.tables
        private val added = linkedMapOf<String, Int>()
        private var skipped = 0
        private val columns = mutableMapOf<String, Set<String>>()

        fun run(): RestoreResult {
            val tags = parentsFirst("tags", "tag_id", "parent_tag_id") { row, parent ->
                val name = row.values["name"]
                one("SELECT tag_id FROM tags WHERE name = ? AND parent_tag_id IS ?", name, parent)
                    ?: freeId("tags", "tag_id", row.key).also { add("tags", row.values + mapOf("tag_id" to it, "parent_tag_id" to parent)) }
            }
            each("media_tags") { it + mapOf("asset_id" to asset(it["asset_id"]), "tag_id" to tags[it["tag_id"]]) }
            each("media_ratings") { it + ("asset_id" to asset(it["asset_id"])) }
            val albums = parentsFirst("albums", "album_id", "parent_album_id") { row, parent ->
                if (exists("albums", "album_id", row.key)) row.key else row.key.also { add("albums", row.values + ("parent_album_id" to parent)) }
            }
            each("album_items") { values ->
                val album = albums[values["album_id"]]?.takeIf { one("SELECT album_kind FROM albums WHERE album_id = ?", it) == "MANUAL" }
                values + mapOf("album_id" to album, "asset_id" to asset(values["asset_id"]))
            }
            restoreMemos()
            each("location_evidence") { it + ("asset_id" to asset(it["asset_id"])) }
            each("location_evidence_support") { values ->
                values + mapOf(
                    "evidence_id" to values["evidence_id"]?.takeIf { exists("location_evidence", "evidence_id", it) },
                    "supporting_asset_id" to asset(values["supporting_asset_id"]),
                )
            }
            val overrides = mutableSetOf<Any?>()
            each("metadata_override_sets", onAdded = { overrides += it["override_id"] }) { it + ("asset_id" to asset(it["asset_id"])) }
            each("metadata_override_values") { values -> values.takeIf { it["override_id"] in overrides } }
            each("asset_privacy_rules") { it + ("asset_id" to asset(it["asset_id"])) }
            restoreSearches(albums)
            return RestoreResult(added, skipped)
        }

        /** Memos of matched photos; a memo on a video scene waits for the scene to be indexed here, so it is left out. */
        private fun restoreMemos() {
            val subjects = mutableMapOf<String, String>()
            for (row in tables["annotation_subjects"].orEmpty()) {
                val local = asset(row.values["asset_id"])
                if (local == null) {
                    skipped++
                    continue
                }
                subjects[row.key] = one("SELECT subject_id FROM annotation_subjects WHERE asset_id = ?", local)
                    ?: "asset:$local".also { add("annotation_subjects", mapOf("subject_id" to it, "asset_id" to local, "segment_id" to null)) }
            }
            val memos = mutableSetOf<Any?>()
            each("semantic_annotations", onAdded = { memos += it["annotation_id"] }) { values ->
                values.takeUnless { exists("semantic_annotations", "annotation_id", it["annotation_id"]) }
                    ?.let { it + ("subject_id" to subjects[it["subject_id"]]) }
            }
            each("semantic_annotation_revisions") { values -> values.takeIf { it["annotation_id"] in memos } }
        }

        /** Saved searches go under this device's search profile; without one yet they are left out. */
        private fun restoreSearches(albums: Map<String, String>) {
            val profile = one("SELECT search_profile_id FROM search_profiles ORDER BY search_profile_id LIMIT 1")
            if (profile == null) {
                skipped += SEARCH_KINDS.sumOf { tables[it].orEmpty().size }
                return
            }
            val searches = mutableSetOf<Any?>()
            each("saved_searches", onAdded = { searches += it["saved_search_id"] }) { it + ("search_profile_id" to profile) }
            parentsFirst("search_rule_nodes", "rule_node_id", "parent_node_id") { row, parent ->
                row.key.also { if (!exists("search_rule_nodes", "rule_node_id", it)) add("search_rule_nodes", row.values + ("parent_node_id" to parent)) }
            }
            each("saved_search_roots") { values -> values.takeIf { it["saved_search_id"] in searches && exists("search_rule_nodes", "rule_node_id", it["root_node_id"]) } }
            each("search_rule_set_values") { values -> values.takeIf { exists("search_rule_nodes", "rule_node_id", it["rule_node_id"]) } }
            each("smart_album_rules") { values ->
                values.takeIf { it["saved_search_id"] in searches }?.let { it + ("album_id" to albums[it["album_id"]]) }
            }
        }

        private fun asset(exported: Any?): String? = assets[exported]

        /** Adds each row of [kind] as [map] makes it; a row it returns null for, or with a null it filled in, is left out. */
        private fun each(kind: String, onAdded: (Map<String, Any?>) -> Unit = {}, map: (Map<String, Any?>) -> Map<String, Any?>?) {
            for (row in tables[kind].orEmpty()) {
                val values = map(row.values)
                if (values == null || values.any { (name, value) -> value == null && row.values[name] != null }) {
                    skipped++
                } else if (add(kind, values)) {
                    onAdded(values)
                }
            }
        }

        /** Rows whose parent is placed first; [place] returns the id here, or null to leave the row and its children out. */
        private fun parentsFirst(kind: String, id: String, parent: String, place: (BackupRow, String?) -> String?): Map<String, String> {
            val placed = mutableMapOf<String, String>()
            var left = tables[kind].orEmpty()
            while (left.isNotEmpty()) {
                val ready = left.filter { row -> (row.values[parent] as String?).let { it == null || it in placed } }
                if (ready.isEmpty()) break
                for (row in ready) {
                    val here = place(row, (row.values[parent] as String?)?.let(placed::get))
                    if (here == null) skipped++ else placed[row.values[id] as String] = here
                }
                left = left - ready.toSet()
            }
            skipped += left.size
            return placed
        }

        private fun freeId(table: String, column: String, wanted: String) = if (exists(table, column, wanted)) UUID.randomUUID().toString() else wanted

        /** INSERT OR IGNORE of the columns [table] has; a name the file made up never reaches the SQL. */
        private fun add(table: String, values: Map<String, Any?>): Boolean {
            val known = columns.getOrPut(table) { strings("SELECT name FROM pragma_table_info(?)", table).toSet() }
            val row = ContentValues()
            for ((name, value) in values) {
                if (name !in known) continue
                when (value) {
                    null -> row.putNull(name)
                    is String -> row.put(name, value)
                    is Long -> row.put(name, value)
                    is Double -> row.put(name, value)
                    is ByteArray -> row.put(name, value)
                    else -> error("Cannot restore a ${value::class.simpleName}")
                }
            }
            val inserted = sql.insert(table, SQLiteDatabase.CONFLICT_IGNORE, row) != -1L
            if (inserted) added.merge(table, 1, Int::plus) else skipped++
            return inserted
        }
    }

    private companion object {
        val SEARCH_KINDS = listOf("saved_searches", "search_rule_nodes", "saved_search_roots", "search_rule_set_values", "smart_album_rules")
    }
}
