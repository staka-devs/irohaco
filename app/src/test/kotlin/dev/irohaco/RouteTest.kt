package dev.irohaco

import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.feature.timeline.library.MediaList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    @Test
    fun `viewer routes keep the asset id and the optional start position`() {
        assertEquals(Route.Viewer("a-1", null, null), Route.parseViewer(Route.viewer("a-1")))
        assertEquals(Route.Viewer("a-1", 42_000L, null), Route.parseViewer(Route.playVideoAt("a-1", 42_000)))
    }

    @Test
    fun `folder routes keep the volume and the path, even odd ones`() {
        val folder = FolderRef("v@1", "Pictures/a@b|c/")
        assertTrue(Route.isFolder(Route.folder(folder)))
        assertFalse(Route.isFolder(Route.viewer("a-1")))
        assertEquals(folder, Route.parseFolder(Route.folder(folder)))
        assertEquals(FolderRef("v", ""), Route.parseFolder(Route.folder(FolderRef("v", ""))))
        assertEquals(Route.Viewer("a-1", null, MediaList.Folder(folder)), Route.parseViewer(Route.viewer("a-1", MediaList.Folder(folder))))
    }

    @Test
    fun `album, tag and favorites routes round-trip`() {
        listOf(MediaList.Album("al-1"), MediaList.Tag("t-1"), MediaList.Favorites).forEach { list ->
            assertTrue(Route.isList(Route.list(list)))
            assertEquals(list, Route.parseList(Route.list(list)))
            assertEquals(Route.Viewer("a-1", null, list), Route.parseViewer(Route.viewer("a-1", list)))
        }
    }
}
