package dev.irohaco.core.application

import dev.irohaco.core.contracts.MediaDetailsReader
import dev.irohaco.core.contracts.ShareFiles
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.Detail
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaDetails
import dev.irohaco.core.domain.MediaDetailsResult
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PrepareShareTest {
    private fun details(location: Detail<GeoPoint> = Detail.Missing) = MediaDetails(
        fileName = "a.jpg", folder = Detail.Missing, sizeBytes = Detail.Missing, mimeType = Detail.Missing,
        widthPx = Detail.Missing, heightPx = Detail.Missing, takenAt = Detail.Missing, durationMs = Detail.Missing,
        camera = Detail.Missing, lens = Detail.Missing, fNumber = Detail.Missing, exposureSeconds = Detail.Missing,
        iso = Detail.Missing, focalLengthMm = Detail.Missing, location = location, description = Detail.Missing, keywords = Detail.Missing,
    )

    private inner class FakeReader(private val locations: Map<String, GeoPoint>) : MediaDetailsReader {
        override suspend fun read(contentUri: String, kind: MediaKind) =
            MediaDetailsResult.Read(details(locations[contentUri]?.let { Detail.Value(it) } ?: Detail.Missing))
    }

    private class FakeShareFiles : ShareFiles {
        val stripped = mutableListOf<String>()
        val kept = mutableListOf<String>()
        var cleanedUp = false
        override suspend fun prepareCopy(contentUri: String, keepLocation: Boolean): String? {
            if (keepLocation) kept += contentUri else stripped += contentUri
            return "content://copy/$contentUri"
        }
        override suspend fun cleanUp() { cleanedUp = true }
    }

    private fun item(id: String, kind: MediaKind = MediaKind.IMAGE) = TimelineItem(id, kind, "content://$id", 0)

    @Test
    fun `finds which items have a location, and which of those cannot have it removed`() = runTest {
        val share = PrepareShare(FakeReader(mapOf("content://a" to GeoPoint(1.0, 2.0))), FakeShareFiles())
        val candidates = share.check(listOf(item("a"), item("b"), item("c", MediaKind.VIDEO)))
        assertEquals(listOf(true, false, false), candidates.map { it.hasLocation })
        assertEquals(listOf(false, false, false), candidates.map { it.cannotRemoveLocation }) // only a video WITH a location counts
    }

    @Test
    fun `a video's location cannot be removed, even when asked to strip`() = runTest {
        val share = PrepareShare(FakeReader(mapOf("content://v" to GeoPoint(1.0, 2.0))), FakeShareFiles())
        val candidates = share.check(listOf(item("v", MediaKind.VIDEO)))
        assertEquals(true, candidates.single().cannotRemoveLocation)
    }

    @Test
    fun `stripping makes a cache copy only for photos that actually have a location`() = runTest {
        val files = FakeShareFiles()
        val share = PrepareShare(FakeReader(mapOf("content://a" to GeoPoint(1.0, 2.0))), files)
        val candidates = share.check(listOf(item("a"), item("b")))
        val shared = share.prepare(candidates, stripLocation = true)
        assertEquals(listOf("content://a"), files.stripped)
        assertEquals(listOf("content://copy/content://a", "content://b"), shared.map { it.contentUri })
    }

    @Test
    fun `keeping the location still makes a copy, since sharing the original redacts it anyway`() = runTest {
        val files = FakeShareFiles()
        val share = PrepareShare(FakeReader(mapOf("content://a" to GeoPoint(1.0, 2.0))), files)
        val candidates = share.check(listOf(item("a")))
        val shared = share.prepare(candidates, stripLocation = false)
        assertEquals(listOf("content://a"), files.kept)
        assertEquals(listOf("content://copy/content://a"), shared.map { it.contentUri })
    }

    @Test
    fun `an item with no location at all needs no copy, either way`() = runTest {
        val files = FakeShareFiles()
        val share = PrepareShare(FakeReader(emptyMap()), files)
        val candidates = share.check(listOf(item("a")))
        assertEquals(listOf("content://a"), share.prepare(candidates, stripLocation = true).map { it.contentUri })
        assertEquals(listOf("content://a"), share.prepare(candidates, stripLocation = false).map { it.contentUri })
        assertEquals(emptyList(), files.stripped + files.kept)
    }

    @Test
    fun `cleans up through the port`() = runTest {
        val files = FakeShareFiles()
        PrepareShare(FakeReader(emptyMap()), files).cleanUp()
        assertEquals(true, files.cleanedUp)
    }
}
