package dev.irohaco

import dev.irohaco.core.contracts.FolderRef
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
        assertEquals(Route.Viewer("a-1", null, folder), Route.parseViewer(Route.viewer("a-1", folder)))
    }
}
