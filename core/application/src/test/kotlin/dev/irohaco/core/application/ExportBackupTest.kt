package dev.irohaco.core.application

import dev.irohaco.core.contracts.BackupArchive
import dev.irohaco.core.contracts.BackupContents
import dev.irohaco.core.contracts.BackupManifest
import dev.irohaco.core.contracts.BackupRow
import dev.irohaco.core.contracts.BackupStore
import dev.irohaco.core.contracts.BackupTables
import dev.irohaco.core.contracts.BackupUnreadableException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A backup counts as done only once the written file read back whole (UC-BACKUP-01). */
class ExportBackupTest {
    private val tables: BackupTables = mapOf("tags" to listOf(BackupRow("t1", mapOf("name" to "海"))))
    private val manifest = BackupManifest("m", mapOf("tags" to mapOf("t1" to "h")))
    private val states = mutableMapOf<String, BackupManifest?>()
    private val begun = mutableListOf<Triple<String, String, Long>>()
    private val store = object : BackupStore {
        override suspend fun snapshot() = tables
        override suspend fun begin(backupId: String, archiveRef: String, createdAt: Long) {
            begun += Triple(backupId, archiveRef, createdAt)
        }
        override suspend fun finish(backupId: String, manifest: BackupManifest?) {
            states[backupId] = manifest
        }
    }
    private var readBack: BackupManifest? = manifest
    private val discarded = mutableListOf<String>()
    private val archive = object : BackupArchive {
        override suspend fun write(target: String, passphrase: CharArray, backupId: String, createdAt: Long, tables: BackupTables): BackupManifest {
            assertEquals(this@ExportBackupTest.tables, tables)
            return manifest
        }
        override suspend fun read(source: String, passphrase: CharArray): BackupContents =
            BackupContents("b1", 7, 1, readBack ?: throw BackupUnreadableException("damaged"), tables)
        override suspend fun discard(target: String) {
            discarded += target
        }
    }
    private val export = ExportBackup(store, archive, { "b1" }, { 7 })

    @Test
    fun `a backup that reads back whole is verified`() = runTest {
        assertEquals(manifest, export("content://doc", "passphrase".toCharArray()))
        assertEquals(listOf(Triple("b1", "content://doc", 7L)), begun)
        assertEquals<Map<String, BackupManifest?>>(mapOf("b1" to manifest), states)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun `a file that does not read back is failed, removed and never reported done`() = runTest {
        readBack = null
        assertFailsWith<BackupUnreadableException> { export("content://doc", "passphrase".toCharArray()) }
        assertEquals(mapOf<String, BackupManifest?>("b1" to null), states)
        assertEquals(listOf("content://doc"), discarded)
        readBack = manifest.copy(sha256 = "other")
        assertFailsWith<IllegalStateException> { export("content://doc", "passphrase".toCharArray()) }
    }

    @Test
    fun `a short passphrase starts nothing`() = runTest {
        assertFailsWith<IllegalArgumentException> { export("content://doc", "short".toCharArray()) }
        assertTrue(begun.isEmpty())
    }
}
