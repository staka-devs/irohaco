package dev.irohaco.core.application

import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.contracts.LibraryLayout
import dev.irohaco.core.contracts.LibraryReader
import dev.irohaco.core.contracts.LibrarySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseLibraryTest {
    private val pinned = MutableStateFlow(emptyList<FolderRef>())

    private val settings = object : LibrarySettings {
        override val excludedFolders: Flow<Set<FolderRef>> = emptyFlow()
        override suspend fun setExcluded(folder: FolderRef, excluded: Boolean) = Unit
        override val pinnedFolders: Flow<List<FolderRef>> = pinned
        override suspend fun setPinnedFolders(folders: List<FolderRef>) {
            pinned.value = folders
        }
        override val libraryLayout: Flow<LibraryLayout> = emptyFlow()
        override suspend fun setLibraryLayout(layout: LibraryLayout) = Unit
    }

    private val reader = object : LibraryReader {
        override suspend fun folders(): List<LibraryFolder> = emptyList()
        override fun changes(): Flow<Unit> = emptyFlow()
    }

    private val a = FolderRef("v", "A/")
    private val b = FolderRef("v", "B/")
    private val c = FolderRef("v", "C/")

    @Test
    fun `pinning appends, unpinning removes`() = runTest {
        val library = BrowseLibrary(reader, settings)
        library.setPinned(a, true)
        library.setPinned(b, true)
        library.setPinned(a, false)
        assertEquals(listOf(b), library.pinnedFolders().first())
    }

    @Test
    fun `moving stops at either end and ignores folders that are not pinned`() = runTest {
        val library = BrowseLibrary(reader, settings)
        pinned.value = listOf(a, b, c)
        library.movePinned(c, -1)
        assertEquals(listOf(a, c, b), pinned.value)
        library.movePinned(a, -1)
        assertEquals(listOf(a, c, b), pinned.value)
        library.movePinned(b, 5)
        assertEquals(listOf(a, c, b), pinned.value)
        library.movePinned(FolderRef("v", "Z/"), 1)
        assertEquals(listOf(a, c, b), pinned.value)
    }
}
