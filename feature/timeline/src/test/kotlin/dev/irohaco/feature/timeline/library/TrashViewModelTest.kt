package dev.irohaco.feature.timeline.library

import dev.irohaco.core.application.BrowseTrash
import dev.irohaco.core.contracts.TrashReader
import dev.irohaco.core.contracts.TrashedItem
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/** viewModelScope needs a Main dispatcher, which only a real Looper normally provides. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    @Before
    fun setMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val a = TrashedItem(1, "content://1", MediaKind.IMAGE, "a.jpg", null)
    private val b = TrashedItem(2, "content://2", MediaKind.IMAGE, "b.jpg", null)
    private var items = listOf(a, b)
    private var changed = 0
    private val purged = mutableListOf<String>()

    private fun viewModel() = TrashViewModel(BrowseTrash(TrashReader { items }) { purged += it }, onChanged = { changed++ })

    @Test
    fun `selects, deselects, selects all and clears`() = runTest {
        val vm = viewModel()
        vm.toggle(a.mediaStoreId)
        assertEquals(setOf(1L), vm.selection.value)
        vm.toggle(a.mediaStoreId)
        assertEquals(emptySet(), vm.selection.value)
        vm.selectAll()
        assertEquals(setOf(1L, 2L), vm.selection.value)
        vm.clearSelection()
        assertEquals(emptySet(), vm.selection.value)
    }

    @Test
    fun `a confirmed change syncs the catalog, clears the selection and re-reads the trash`() = runTest {
        val vm = viewModel()
        vm.toggle(a.mediaStoreId)
        items = listOf(b) // the restore/delete already happened in MediaStore by the time this runs
        vm.changed()
        assertEquals(1, changed)
        assertEquals(emptySet(), vm.selection.value)
        assertEquals(listOf(b), vm.items.value)
    }

    @Test
    fun `files deleted for good leave the DB, and only the selected ones`() = runTest {
        val vm = viewModel()
        vm.toggle(b.mediaStoreId)
        items = listOf(a)
        vm.deletedForever()
        assertEquals(listOf("content://2"), purged)
        assertEquals(1, changed)
        assertEquals(listOf(a), vm.items.value)
    }
}
