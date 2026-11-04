package dev.irohaco.core.application

import dev.irohaco.core.contracts.BackupArchive
import dev.irohaco.core.contracts.BackupContents
import dev.irohaco.core.contracts.RestoreMatches
import dev.irohaco.core.contracts.RestoreResult
import dev.irohaco.core.contracts.RestoreStore

/**
 * Restores a backup in two steps (UC-RESTORE-01, AC-011): [preview] opens and checks the file and
 * matches its files here, and [apply] adds the records only after the user saw the counts. API keys,
 * grants and OS permissions are never in a backup, so they never come back.
 */
class RestoreBackup(
    private val archive: BackupArchive,
    private val store: RestoreStore,
    private val newId: () -> String,
    private val now: () -> Long,
) {
    /** A checked backup and what was found of it here; the records stay in memory until applied. */
    class Preview internal constructor(val restoreId: String, val contents: BackupContents, val matches: RestoreMatches) {
        val records get() = contents.tables.values.sumOf { it.size }
    }

    /** Throws [dev.irohaco.core.contracts.BackupUnreadableException] for a wrong passphrase or a damaged file. */
    suspend fun preview(source: String, passphrase: CharArray): Preview {
        val contents = archive.read(source, passphrase)
        val restoreId = newId()
        return Preview(restoreId, contents, store.stage(restoreId, source, contents, now()))
    }

    suspend fun apply(preview: Preview): RestoreResult = store.apply(preview.restoreId, preview.contents, now())
}
