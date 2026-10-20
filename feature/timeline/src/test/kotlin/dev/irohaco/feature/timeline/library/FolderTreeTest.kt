package dev.irohaco.feature.timeline.library

import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.LibraryFolder
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTreeTest {
    private fun folder(path: String) = LibraryFolder(FolderRef("v", path), "external_primary", offline = false, itemCount = 1)

    @Test
    fun `parents without media get a line and the root comes first`() {
        val lines = folderTree(listOf(folder(""), folder("DCIM/Camera/"), folder("Pictures/Trip/2026/"), folder("Pictures/")))
        assertEquals(
            listOf("" to 0, "DCIM/" to 1, "DCIM/Camera/" to 2, "Pictures/" to 1, "Pictures/Trip/" to 2, "Pictures/Trip/2026/" to 3),
            lines.map { it.path to it.depth },
        )
        assertEquals(listOf(true, false, true, true, false, true), lines.map { it.folder != null })
        assertEquals("Camera", lines[2].name)
    }
}
