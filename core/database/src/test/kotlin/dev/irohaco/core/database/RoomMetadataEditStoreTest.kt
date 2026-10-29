package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.InferredPlace
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.MetadataEdits
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.domain.GeoBounds
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Edits of capture time, location and description kept in the DB (PRD MET-002, MET-005). */
@RunWith(RobolectricTestRunner::class)
class RoomMetadataEditStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private var id = 0
    private val edits = RoomMetadataEditStore(db, now = { 1 }) { "o${id++}" }
    private val locations = RoomLocationStore(db) { "e${id++}" }
    private val timeline = RoomTimelineReader(db)
    private val tokyo = GeoPoint(35.68, 139.76)
    private val osaka = GeoPoint(34.69, 135.50)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    private suspend fun catalog(vararg names: String) = RoomCatalogStore(db).apply(
        CatalogChanges(
            observedAtMs = 1,
            newVolumes = listOf(CatalogVolume("v1", "external_primary", null, null)),
            newAssets = names.mapIndexed { i, name ->
                NewCatalogAsset("d-$name", name, "s-$name", "v1", MediaStoreEntry(i + 1L, MediaKind.IMAGE, "content://media/$i", "$name.jpg", null, "image/jpeg", 1, 1, takenAtMs = (i + 1) * 1000L))
            },
        ),
    )

    private suspend fun order() = timeline.page(TimelineQuery(), 0, 10).map { it.assetId }

    @Test
    fun `an edited capture time orders the timeline until it is reverted`() = runTest {
        catalog("a", "b")
        assertEquals(listOf("b", "a"), order())
        edits.setTakenAt("a", 5000)
        assertEquals(listOf("a", "b"), order())
        assertEquals(5000, timeline.page(TimelineQuery(), 0, 1).single().sortTimeMs)
        edits.setTakenAt("a", 500)
        assertEquals(MetadataEdits(takenAtMs = 500), edits.edits("a").first())
        edits.setTakenAt("a", null)
        assertEquals(MetadataEdits(), edits.edits("a").first())
        assertEquals(listOf("b", "a"), order())
    }

    @Test
    fun `a description can be replaced, removed and reverted`() = runTest {
        catalog("a")
        edits.setDescription("a", FieldEdit.Set("Harbour at dawn"))
        assertEquals(FieldEdit.Set("Harbour at dawn"), edits.edits("a").first().description)
        edits.setDescription("a", FieldEdit.Removed)
        assertEquals(FieldEdit.Removed, edits.edits("a").first().description)
        edits.setDescription("a", FieldEdit.Set("Harbour"))
        assertEquals(FieldEdit.Set("Harbour"), edits.edits("a").first().description)
        edits.setDescription("a", null)
        assertEquals(null, edits.edits("a").first().description)
    }

    @Test
    fun `the user's location replaces the file's on the map and in the area filter`() = runTest {
        catalog("a", "b")
        locations.recordExif("a", tokyo)
        locations.recordExif("b", tokyo)
        edits.setLocation("a", FieldEdit.Set(osaka))
        assertEquals(FieldEdit.Set(osaka), edits.edits("a").first().location)
        assertEquals(mapOf("a" to osaka, "b" to tokyo), locations.located().associate { it.assetId to it.point })
        val kansai = TimelineQuery(area = GeoBounds(south = 34.0, west = 135.0, north = 35.0, east = 136.0))
        assertEquals(listOf("a"), timeline.page(kansai, 0, 10).map { it.assetId })

        edits.setLocation("b", FieldEdit.Removed)
        assertEquals(listOf("a"), locations.located().map { it.assetId })

        edits.setLocation("a", null)
        edits.setLocation("b", null)
        assertEquals(mapOf("a" to tokyo, "b" to tokyo), locations.located().associate { it.assetId to it.point })
        assertEquals(MetadataEdits(), edits.edits("a").first())
    }

    @Test
    fun `a location set by the user is not guessed and replaces an earlier guess`() = runTest {
        catalog("located", "a")
        locations.recordExif("located", tokyo)
        locations.recordExif("a", null)
        locations.replaceInferred(listOf(InferredPlace("a", tokyo, "Tokyo", 0.8, "located")))
        assertEquals(listOf("a"), locations.unlocatedTimes().map { it.first })

        edits.setLocation("a", FieldEdit.Set(osaka))
        assertEquals(emptyList(), locations.unlocatedTimes())
        assertEquals(emptyList(), locations.filesAt(listOf("Tokyo"), includeInferred = true, limit = 10).filter { it.assetId == "a" })
    }

    @Test
    fun `a place name is looked up again for the user's location`() = runTest {
        catalog("a")
        locations.recordExif("a", tokyo)
        locations.setPlaceName("a", "Tokyo")
        edits.setLocation("a", FieldEdit.Set(osaka))
        assertEquals(null, locations.placeName("a"))
        assertEquals(listOf(GeoPoint(34.69, 135.5)), locations.unnamedAreas(10))
        locations.nameArea(GeoPoint(34.69, 135.5), "Osaka")
        assertEquals("Osaka", locations.placeName("a"))
    }
}
