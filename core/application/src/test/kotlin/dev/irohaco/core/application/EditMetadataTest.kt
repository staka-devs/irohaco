package dev.irohaco.core.application

import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.MetadataEditStore
import dev.irohaco.core.contracts.MetadataEdits
import dev.irohaco.core.contracts.MetadataField
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaDetails
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** The file's values and the edits as the text the store compares (INT-002). */
class EditMetadataTest {
    private class Store(private val current: MetadataEdits) : MetadataEditStore {
        var compared: Pair<Map<MetadataField, String?>, Map<MetadataField, String?>>? = null

        override fun edits(assetId: String) = flowOf(current)
        override suspend fun setTakenAt(assetId: String, takenAtMs: Long?) = Unit
        override suspend fun setLocation(assetId: String, edit: FieldEdit<GeoPoint>?) = Unit
        override suspend fun setDescription(assetId: String, edit: FieldEdit<String>?) = Unit
        override suspend fun keepEdit(assetId: String, field: MetadataField) = Unit
        override suspend fun compareWithFile(assetId: String, file: Map<MetadataField, String?>, edited: Map<MetadataField, String?>) {
            compared = file to edited
        }
    }

    private fun file(takenAt: Detail<LocalDateTime>, location: Detail<GeoPoint>, description: Detail<String>) = MediaDetails(
        "a.jpg", Detail.Missing, Detail.Missing, Detail.Missing, Detail.Missing, Detail.Missing, takenAt, Detail.Missing,
        Detail.Missing, Detail.Missing, Detail.Missing, Detail.Missing, Detail.Missing, Detail.Missing, location, description, Detail.Missing,
    )

    @Test
    fun `withheld values are left out and the edited time is read in the zone it was picked in`() = runTest {
        val store = Store(MetadataEdits(takenAtMs = 1_761_701_445_000, location = FieldEdit.Removed, description = FieldEdit.Set("Pier")))
        val read = file(Detail.Value(LocalDateTime.of(2025, 10, 29, 1, 30, 45)), Detail.NoPermission, Detail.Missing)
        EditMetadata(store).compareWithFile("a", read, ZoneOffset.UTC)
        assertEquals(
            mapOf(MetadataField.TAKEN to "2025-10-29T01:30:45", MetadataField.DESCRIPTION to null) to
                mapOf(MetadataField.TAKEN to "2025-10-29T01:30:45", MetadataField.LOCATION to null, MetadataField.DESCRIPTION to "Pier"),
            store.compared,
        )
    }
}
