package dev.irohaco.feature.timeline.library

import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.LibraryFolder
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTreeTest {
    private fun folder(path: String, count: Int = 1) =
        LibraryFolder(FolderRef("v", path), "external_primary", offline = false, itemCount = count, coverUri = "cover:$path")

    private val lines = folderTree(listOf(folder(""), folder("DCIM/Camera/", 3), folder("Pictures/Trip/2026/", 2), folder("Pictures/", 5)))

    @Test
    fun `parents without media get a line and the root comes first`() {
        assertEquals(
            listOf("" to 0, "DCIM/" to 1, "DCIM/Camera/" to 2, "Pictures/" to 1, "Pictures/Trip/" to 2, "Pictures/Trip/2026/" to 3),
            lines.map { it.path to it.depth },
        )
        assertEquals(listOf(true, false, true, true, false, true), lines.map { it.folder != null })
        assertEquals("Camera", lines[2].name)
    }

    @Test
    fun `counts and covers include subfolders, and only folders with subfolders fold`() {
        assertEquals(listOf(1, 3, 3, 7, 2, 2), lines.map { it.totalCount })
        assertEquals(listOf(false, true, false, true, true, false), lines.map { it.hasChildren })
        assertEquals("cover:DCIM/Camera/", lines[1].coverUri) // a parent borrows a subfolder's cover
        assertEquals("cover:Pictures/", lines[3].coverUri) // its own media comes first
    }

    @Test
    fun `collapsed folders hide their subtree`() {
        // The volume root is the volume node itself, so it never gets a line.
        assertEquals(listOf("DCIM/", "Pictures/"), visibleLines(lines, emptySet()).map { it.path })
        assertEquals(listOf("DCIM/", "Pictures/", "Pictures/Trip/"), visibleLines(lines, setOf("Pictures/")).map { it.path })
        // A subfolder stays hidden while its grandparent is collapsed, even if it is expanded itself.
        assertEquals(listOf("DCIM/", "Pictures/"), visibleLines(lines, setOf("Pictures/Trip/")).map { it.path })
    }

    @Test
    fun `children and breadcrumbs`() {
        assertEquals(listOf("DCIM/", "Pictures/"), childrenOf(lines, "").map { it.path })
        assertEquals(listOf("Pictures/Trip/"), childrenOf(lines, "Pictures/").map { it.path })
        assertEquals(emptyList<String>(), childrenOf(lines, "DCIM/Camera/").map { it.path })
        assertEquals(listOf("Pictures" to "Pictures/", "Trip" to "Pictures/Trip/"), breadcrumbs("Pictures/Trip/"))
        assertEquals(emptyList<Pair<String, String>>(), breadcrumbs(""))
    }
}
