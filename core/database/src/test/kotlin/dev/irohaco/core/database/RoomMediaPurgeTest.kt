package dev.irohaco.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.irohaco.core.contracts.AnnotationAuthor
import dev.irohaco.core.contracts.AnnotationKind
import dev.irohaco.core.contracts.CatalogChanges
import dev.irohaco.core.contracts.CatalogVolume
import dev.irohaco.core.contracts.EmbeddingSpaceSpec
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.IndexJobKind
import dev.irohaco.core.contracts.IndexJobState
import dev.irohaco.core.contracts.MediaStoreEntry
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.NewCatalogAsset
import dev.irohaco.core.contracts.VideoQuality
import dev.irohaco.core.domain.AssetAvailability
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** A file deleted for good leaves no row behind; one with another copy keeps its data on the copy (ADR-0018). */
@RunWith(RobolectricTestRunner::class)
class RoomMediaPurgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = IrohacoDatabaseFactory.open(context)
    private val sql get() = db.openHelper.writableDatabase

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(IrohacoDatabaseFactory.NAME)
    }

    @Test
    fun `deleting for good removes every row of the file, and a copy keeps the asset`() = runTest {
        RoomCatalogStore(db).apply(
            CatalogChanges(
                observedAtMs = 1,
                newVolumes = listOf(CatalogVolume("v", "external_primary", null, null)),
                onlineVolumeIds = setOf("v"),
                newAssets = listOf(
                    NewCatalogAsset("d-gone", "gone", "s-gone", "v", entry(1, MediaKind.IMAGE)),
                    NewCatalogAsset("d-video", "video", "s-video", "v", entry(2, MediaKind.VIDEO)),
                    NewCatalogAsset("d-kept", "kept", "s-kept", "v", entry(3, MediaKind.IMAGE)),
                    NewCatalogAsset("d-copied", "copied", "s-copied", "v", entry(4, MediaKind.IMAGE)),
                ),
            ),
        )
        // A second copy of "copied" that stays on the device.
        sql.execSQL(
            """INSERT INTO media_sources(source_id, asset_id, volume_id, media_store_id, content_uri, display_name, mime_type, file_size_bytes, access_state)
               VALUES ('s-copy2', 'copied', 'v', 5, '${uri(5)}', '5', 'image/jpeg', 10, 'AVAILABLE')""",
        )
        RoomCatalogStore(db).apply(CatalogChanges(observedAtMs = 2, availability = mapOf("s-gone" to AssetAvailability.INACCESSIBLE)))

        for (asset in listOf("gone", "video", "kept", "copied")) {
            RoomCollectionsStore(db).addTag(listOf(asset), "trip")
            RoomMarksStore(db).setRating(asset, 5)
            RoomAnnotationStore(db).create("memo-$asset", asset, AnnotationKind.CONTEXT, "harbour", AnnotationAuthor.USER, null, 0)
            RoomMetadataEditStore(db).setDescription(asset, FieldEdit.Set("Pier"))
            RoomLocationStore(db).recordExif(asset, GeoPoint(35.0, 139.0))
        }
        RoomCollectionsStore(db).let { it.addToAlbum(it.createAlbum("Holiday"), listOf("gone", "kept")) }

        RoomModelProfiles(db).register(ModelArtifact("m", "Model", "https://example.invalid/m", 1, "d"))
        val media = RoomMediaIndexStore(db)
        val space = media.ensureSpace("s1", EmbeddingSpaceSpec("m", "d", 2, "L2", "q: ", null, "p"), "sig")
        val items = media.plan(space, null, VideoQuality.BALANCED, 0)
        val jobs = RoomIndexJobStore(db)
        jobs.create("job", space, IndexJobKind.INITIAL, true, 0, items)
        jobs.transition("job", setOf(IndexJobState.PLANNED), IndexJobState.RUNNING)
        items.forEach { jobs.commit("job", it, "vec-${it.targetId}", floatArrayOf(1f, 0f), 0) }
        items.filter { !it.targetId.startsWith("memo:") }.forEach { RoomTextIndexStore(db).recordOcr(it.targetId, it.sourceRevisionDigest, "STATION", "e") }

        assertEquals(true, count("SELECT count(*) FROM sampled_frames") > 0)
        assertEquals(true, count("SELECT count(*) FROM media_access_events") > 0)

        RoomMediaPurge(db).purge(listOf(uri(1), uri(2), uri(4), "content://media/external_primary/images/media/99"))

        assertEquals(listOf("copied", "kept"), strings("SELECT asset_id FROM media_assets ORDER BY asset_id"))
        assertEquals(listOf("s-copy2", "s-kept"), strings("SELECT source_id FROM media_sources ORDER BY source_id"))
        assertEquals(emptyList(), strings("PRAGMA foreign_key_check"))
        // The kept files keep all of theirs.
        assertEquals(2, count("SELECT count(*) FROM media_tags"))
        assertEquals(2, count("SELECT count(*) FROM media_ratings"))
        assertEquals(1, count("SELECT count(*) FROM album_items"))
        assertEquals(2, count("SELECT count(*) FROM semantic_annotations"))
        assertEquals(2, count("SELECT count(*) FROM ocr_observations"))
        assertEquals(2, count("SELECT count(*) FROM ocr_fts"))
        assertEquals(2, count("SELECT count(*) FROM annotation_fts"))
        assertEquals(0, count("SELECT count(*) FROM sampled_frames"))
        assertEquals(0, count("SELECT count(*) FROM media_access_events"))
        assertEquals(4, count("SELECT count(*) FROM embedding_vectors")) // two photos and two memos
    }

    private fun uri(id: Long) = "content://media/external_primary/${if (id == 2L) "video" else "images"}/media/$id"

    private fun entry(id: Long, kind: MediaKind) = MediaStoreEntry(
        mediaStoreId = id,
        kind = kind,
        contentUri = uri(id),
        displayName = "$id",
        relativePath = "DCIM/",
        mimeType = if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4",
        sizeBytes = 10,
        modifiedAtMs = 1,
        takenAtMs = null,
        durationMs = if (kind == MediaKind.VIDEO) 46_000 else null,
    )

    private fun strings(query: String): List<String> = sql.query(query).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }

    private fun count(query: String): Int = sql.query(query).use { it.moveToFirst(); it.getInt(0) }
}
