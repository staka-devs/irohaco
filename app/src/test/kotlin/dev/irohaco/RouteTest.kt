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
    fun `a viewer over a folder keeps the volume and the path, even odd ones`() {
        val folder = MediaList.Folder(FolderRef("v@1", "Pictures/a@b|c/"))
        assertEquals(Route.Viewer("a-1", null, folder), Route.parseViewer(Route.viewer("a-1", folder)))
        val root = MediaList.Folder(FolderRef("v", ""))
        assertEquals(Route.Viewer("a-1", null, root), Route.parseViewer(Route.viewer("a-1", root)))
        assertFalse(Route.isList(Route.viewer("a-1", root)))
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
