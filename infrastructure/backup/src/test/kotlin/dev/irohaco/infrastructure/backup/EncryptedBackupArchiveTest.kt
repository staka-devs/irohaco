package dev.irohaco.infrastructure.backup

import dev.irohaco.core.contracts.BackupRow
import dev.irohaco.core.contracts.BackupUnreadableException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The backup file keeps every record, opens only with its passphrase, and refuses any change (ADR-0010, UC-RESTORE-01 step 1). */
@RunWith(RobolectricTestRunner::class)
class EncryptedBackupArchiveTest {
    private val files = mutableMapOf<String, ByteArray>()
    private val archive = EncryptedBackupArchive(
        openOutput = { name -> object : ByteArrayOutputStream() { override fun close() { files[name] = toByteArray() } } },
        openInput = { name -> ByteArrayInputStream(files.getValue(name)) },
        delete = { files.remove(it) },
        kdf = EncryptedBackupArchive.Kdf(iterations = 1, memoryKiB = 64, parallelism = 1), // the app uses 64 MiB; the tests stay fast
    )
    private val tables = mapOf(
        "tags" to listOf(BackupRow("t1", mapOf("tag_id" to "t1", "name" to "海", "parent_tag_id" to null))),
        "location_evidence" to listOf(BackupRow("e1", mapOf("evidence_id" to "e1", "latitude" to 35.6812, "confidence" to 0.5))),
        "metadata_override_values" to listOf(BackupRow("o1|0", mapOf("override_id" to "o1", "occurrence" to 0L, "blob_value" to byteArrayOf(1, 2, 3)))),
    )
    private val passphrase = "correct horse".toCharArray()

    @Test
    fun `every record comes back as it was written`() = runTest {
        val written = archive.write("f", passphrase, "b1", 42, tables)
        val read = archive.read("f", passphrase)
        assertEquals("b1" to 42L, read.backupId to read.createdAt)
        assertEquals(written, read.manifest)
        assertEquals(setOf("t1"), written.entries.getValue("tags").keys)
        assertEquals(tables.getValue("tags"), read.tables.getValue("tags"))
        assertEquals(35.6812, read.tables.getValue("location_evidence").single().values["latitude"])
        assertContentEquals(byteArrayOf(1, 2, 3), read.tables.getValue("metadata_override_values").single().values["blob_value"] as ByteArray)
        assertEquals(0L, read.tables.getValue("metadata_override_values").single().values["occurrence"])
    }

    @Test
    fun `nothing is readable in the file without the passphrase`() = runTest {
        archive.write("f", passphrase, "b1", 42, tables)
        assertFalse(String(files.getValue("f")).contains("海") || String(files.getValue("f")).contains("tag_id"))
        assertFailsWith<BackupUnreadableException> { archive.read("f", "wrong horse".toCharArray()) }
    }

    @Test
    fun `a changed byte anywhere, header included, is refused`() = runTest {
        archive.write("f", passphrase, "b1", 42, tables)
        val good = files.getValue("f")
        for (at in listOf(good.size - 1, good.size / 2, 30)) {
            files["f"] = good.copyOf().also { it[at] = (it[at] + 1).toByte() }
            assertFailsWith<BackupUnreadableException> { archive.read("f", passphrase) }
        }
        files["f"] = good.copyOf(good.size - 20)
        assertFailsWith<BackupUnreadableException> { archive.read("f", passphrase) }
        files["f"] = "not a backup".toByteArray()
        assertFailsWith<BackupUnreadableException> { archive.read("f", passphrase) }
    }

    @Test
    fun `a header asking for too much memory is refused before the key is made`() = runTest {
        archive.write("f", passphrase, "b1", 42, tables)
        val bytes = files.getValue("f").copyOf()
        // After the 14-byte name and the version: iterations, then memory in KiB.
        ByteBuffer.wrap(bytes, 19, 4).putInt(Int.MAX_VALUE)
        files["f"] = bytes
        val e = assertFailsWith<BackupUnreadableException> { archive.read("f", passphrase) }
        assertTrue("range" in e.message.orEmpty())
    }

    @Test
    fun `a failed export's file can be removed`() = runTest {
        archive.write("f", passphrase, "b1", 42, tables)
        archive.discard("f")
        assertFalse("f" in files)
    }
}
