package dev.irohaco.core.application

import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.core.contracts.MediaFiles
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrganizeMediaTest {
    private class FakeFiles : MediaFiles {
        val names = mutableMapOf("content://1" to "IMG_1.jpg", "content://2" to "IMG_2.jpg")
        val moved = mutableListOf<String>()
        var failOn: String? = null

        override suspend fun folders() = listOf("DCIM/Camera/")
        override suspend fun name(contentUri: String) = names[contentUri]
        override suspend fun move(contentUri: String, folder: String): FileOperationResult {
            if (contentUri == failOn) return FileOperationResult.NAME_TAKEN
            moved += contentUri
            return FileOperationResult.DONE
        }
        override suspend fun copy(contentUri: String, folder: String) = FileOperationResult.DONE
        override suspend fun rename(contentUri: String, fileName: String): FileOperationResult {
            names[contentUri] = fileName
            return FileOperationResult.DONE
        }
    }

    private fun item(n: Int) = TimelineItem("a$n", MediaKind.IMAGE, "content://$n", 0)

    @Test
    fun `a batch stops at the first failure and reports it`() = runTest {
        val files = FakeFiles().apply { failOn = "content://1" }
        assertEquals(FileOperationResult.NAME_TAKEN, OrganizeMedia(files).move(listOf(item(1), item(2)), "Pictures/Trip/"))
        assertEquals(emptyList(), files.moved)
    }

    @Test
    fun `rename keeps the extension and rejects invalid names`() = runTest {
        val files = FakeFiles()
        val organize = OrganizeMedia(files)
        assertEquals(FileOperationResult.DONE, organize.rename(item(1), "harbour"))
        assertEquals("harbour.jpg", files.names["content://1"])
        assertEquals(FileOperationResult.NOT_FOUND, organize.rename(item(3), "x"))
        assertFailsWith<IllegalArgumentException> { organize.rename(item(1), "a/b") }
    }
}
