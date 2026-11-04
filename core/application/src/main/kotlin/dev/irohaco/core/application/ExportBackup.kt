package dev.irohaco.core.application

import dev.irohaco.core.contracts.BackupArchive
import dev.irohaco.core.contracts.BackupManifest
import dev.irohaco.core.contracts.BackupStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Exports the user's data, encrypted, to a file the user picked (UC-BACKUP-01, ADR-0010). The file is
 * read back and checked before the backup counts as done; anything short of that is FAILED and thrown,
 * never shown as a success.
 */
class ExportBackup(
    private val store: BackupStore,
    private val archive: BackupArchive,
    private val newId: () -> String,
    private val now: () -> Long,
) {
    suspend operator fun invoke(target: String, passphrase: CharArray): BackupManifest {
        require(passphrase.size >= MIN_PASSPHRASE) { "The passphrase is too short" }
        val backupId = newId()
        val createdAt = now()
        store.begin(backupId, target, createdAt)
        try {
            val written = archive.write(target, passphrase, backupId, createdAt, store.snapshot())
            val back = archive.read(target, passphrase)
            check(back.backupId == backupId && back.manifest == written) { "The file read back differs from what was written" }
            store.finish(backupId, written)
            return written
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                store.finish(backupId, null)
                runCatching { archive.discard(target) }
            }
            throw e
        }
    }

    companion object {
        /** The key is only as strong as the passphrase; the screen asks for at least this many characters. */
        const val MIN_PASSPHRASE = 8
    }
}
