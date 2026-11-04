package dev.irohaco.core.contracts

/**
 * One record of the user's data as it is exported (UC-BACKUP-01): [key] is its stable id within its
 * kind, and [values] its columns, each a String, Long, Double, ByteArray or null.
 */
data class BackupRow(val key: String, val values: Map<String, Any?>)

/** The records of a backup, by kind; a kind is a table, or `assets` for what each file can be found again by. */
typealias BackupTables = Map<String, List<BackupRow>>

/** What a backup holds: the SHA-256 of each record, by kind and key, and of the manifest itself. */
data class BackupManifest(val sha256: String, val entries: Map<String, Map<String, String>>)

/** A backup opened and checked: every record matched its manifest. */
data class BackupContents(
    val backupId: String,
    val createdAt: Long,
    val formatVersion: Int,
    val manifest: BackupManifest,
    val tables: BackupTables,
)

/** The passphrase is wrong, or the file is damaged, cut short, too large or of an unknown version; nothing of it is used. */
class BackupUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The user's data in the database (ADR-0010): the originals, vectors, keys, grants and OS permissions
 * are never part of it.
 */
interface BackupStore {
    /** Every record to export, read in one transaction so the kinds agree with each other. */
    suspend fun snapshot(): BackupTables

    /** Records a backup being written to [archiveRef]; it is not done until [finish]. */
    suspend fun begin(backupId: String, archiveRef: String, createdAt: Long)

    /** VERIFIED with its manifest when the written file read back whole, else FAILED. */
    suspend fun finish(backupId: String, manifest: BackupManifest?)
}

/** The encrypted backup file (ADR-0010): a key from the passphrase, AES-256-GCM over the records. */
interface BackupArchive {
    /** Writes [tables] to [target], a document the user picked, and returns what it wrote. */
    suspend fun write(target: String, passphrase: CharArray, backupId: String, createdAt: Long, tables: BackupTables): BackupManifest

    /** Opens [source] and checks every record; throws [BackupUnreadableException] when anything does not hold. */
    suspend fun read(source: String, passphrase: CharArray): BackupContents

    /** Removes a file a failed export left, so only a checked backup stays where the user put it. */
    suspend fun discard(target: String)
}

/** How many files of a backup were found on this device (AC-011). */
data class RestoreMatches(val matched: Int, val needsReview: Int, val missing: Int)

/** Where each kind of record went: how many were added, and how many were left out. */
data class RestoreResult(val added: Map<String, Int>, val skipped: Int)

/**
 * Restoring in stages (UC-RESTORE-01): a backup is matched against this device's files first, and
 * only applied after the user saw the counts. Nothing is written to the user's data before [apply].
 */
interface RestoreStore {
    /**
     * Records the session and matches each file of [contents] here: one file with the same name, size
     * and capture time is MATCHED, more than one NEEDS_REVIEW, none is missing. Nothing ambiguous is merged.
     */
    suspend fun stage(restoreId: String, source: String, contents: BackupContents, startedAt: Long): RestoreMatches

    /**
     * Adds the records of the MATCHED files in one transaction, never overwriting what is here, checks
     * the foreign keys and commits; on any failure nothing is kept and the session is ROLLED_BACK.
     */
    suspend fun apply(restoreId: String, contents: BackupContents, completedAt: Long): RestoreResult
}
