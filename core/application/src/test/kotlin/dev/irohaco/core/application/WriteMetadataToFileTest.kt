package dev.irohaco.core.application

import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.MetadataEditStore
import dev.irohaco.core.contracts.MetadataEdits
import dev.irohaco.core.contracts.MetadataField
import dev.irohaco.core.contracts.MetadataFiles
import dev.irohaco.core.contracts.MetadataWriteJournal
import dev.irohaco.core.contracts.MetadataWriteState
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaFormat
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The journaled steps of writing edits to the file (ADR-0004, AC-008). */
class WriteMetadataToFileTest {
    private class Store : MetadataEditStore {
        override fun edits(assetId: String) = flowOf(MetadataEdits(takenAtMs = 0, location = FieldEdit.Removed, description = FieldEdit.Set("Pier")))
        override suspend fun setTakenAt(assetId: String, takenAtMs: Long?) = Unit
        override suspend fun setLocation(assetId: String, edit: FieldEdit<GeoPoint>?) = Unit
        override suspend fun setDescription(assetId: String, edit: FieldEdit<String>?) = Unit
        override suspend fun keepEdit(assetId: String, field: MetadataField) = Unit
        override suspend fun compareWithFile(assetId: String, file: Map<MetadataField, String?>, edited: Map<MetadataField, String?>) = Unit
    }

    private class Journal : MetadataWriteJournal {
        val states = mutableListOf<MetadataWriteState>()
        var committed: Map<MetadataField, String?>? = null
        var closed = false
        var stopped = mapOf<String, MetadataWriteState>()

        override suspend fun begin(assetId: String, fields: Set<MetadataField>) = "op".also { states += MetadataWriteState.REQUESTED }
        override suspend fun advance(operationId: String, state: MetadataWriteState) {
            states += state
        }
        override suspend fun commit(operationId: String, written: Map<MetadataField, String?>) {
            committed = written
        }
        override suspend fun close(operationId: String) {
            closed = true
        }
        override fun recoveryRequired(assetId: String) = flowOf<String?>(null)
        override suspend fun unfinished() = stopped
    }

    private class Files(val stages: Boolean = true, val verifies: Boolean = true) : MetadataFiles {
        var replaced = false
        var discarded = false
        var staged: Map<MetadataField, String?>? = null

        override suspend fun stage(operationId: String, contentUri: String, values: Map<MetadataField, String?>) = stages.also { staged = values }
        override suspend fun replace(operationId: String, contentUri: String) = true.also { replaced = true }
        override suspend fun verify(operationId: String, contentUri: String, values: Map<MetadataField, String?>) = verifies
        override suspend fun restore(operationId: String, contentUri: String) = true
        override suspend fun discard(operationId: String) {
            discarded = true
        }
        override suspend fun saveCopy(contentUri: String, values: Map<MetadataField, String?>, removeLocation: Boolean) = true
    }

    private val item = TimelineItem("a", MediaKind.IMAGE, "content://a", 0, mimeType = "image/jpeg")

    @Test
    fun `only the chosen fields are written, and a checked write is committed`() = runTest {
        val journal = Journal()
        val files = Files()
        val write = WriteMetadataToFile(Store(), journal, files) { ZoneOffset.UTC }
        assertEquals(FileWriteResult.WRITTEN, write.write(item, setOf(MetadataField.TAKEN, MetadataField.LOCATION)))
        val written = mapOf(MetadataField.TAKEN to "1970-01-01T00:00", MetadataField.LOCATION to null)
        assertEquals(written, files.staged)
        assertEquals(written, journal.committed)
        assertEquals(listOf(MetadataWriteState.REQUESTED, MetadataWriteState.STAGED, MetadataWriteState.WRITE_PENDING, MetadataWriteState.VERIFYING), journal.states)
        assertTrue(files.discarded)
    }

    @Test
    fun `a copy that fails its check leaves the file untouched, and a file that fails waits for the user`() = runTest {
        val unstaged = Files(stages = false)
        val journal = Journal()
        assertEquals(FileWriteResult.NOT_WRITTEN, WriteMetadataToFile(Store(), journal, unstaged).write(item, setOf(MetadataField.DESCRIPTION)))
        assertFalse(unstaged.replaced)
        assertTrue(journal.closed)

        val unverified = Files(verifies = false)
        val failed = Journal()
        assertEquals(FileWriteResult.CHECK_FAILED, WriteMetadataToFile(Store(), failed, unverified).write(item, setOf(MetadataField.DESCRIPTION)))
        assertEquals(MetadataWriteState.RECOVERY_REQUIRED, failed.states.last())
        assertFalse(unverified.discarded) // the backup is the way back
    }

    @Test
    fun `a write a stopped process left is settled once, before the file is touched or after`() = runTest {
        val journal = Journal().apply { stopped = mapOf("staged" to MetadataWriteState.STAGED) }
        WriteMetadataToFile(Store(), journal, Files()).recoveryRequired("a").first()
        assertTrue(journal.closed)

        val touched = Journal().apply { stopped = mapOf("writing" to MetadataWriteState.WRITE_PENDING) }
        val write = WriteMetadataToFile(Store(), touched, Files())
        write.recoveryRequired("a").first()
        write.recoveryRequired("a").first()
        assertEquals(listOf(MetadataWriteState.RECOVERY_REQUIRED), touched.states)
        assertFalse(touched.closed)
    }

    @Test
    fun `only a JPEG with nothing after its image is written in place`() {
        val write = WriteMetadataToFile(Store(), Journal(), Files())
        assertTrue(write.canWriteFile(item, MediaFormat()))
        assertFalse(write.canWriteFile(item, MediaFormat(motionPhoto = true)))
        assertFalse(write.canWriteFile(item, MediaFormat(hdr = true)))
        assertFalse(write.canWriteFile(item.copy(mimeType = "image/heic"), MediaFormat()))
        assertTrue(write.canSaveCopy(item.copy(mimeType = "image/png")))
    }
}
