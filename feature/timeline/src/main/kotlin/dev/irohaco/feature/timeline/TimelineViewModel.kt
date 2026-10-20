package dev.irohaco.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.irohaco.core.application.GetLibraryAccess
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.application.OrganizeMedia
import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

class TimelineViewModel(
    getPage: GetTimelinePage,
    private val getAccess: GetLibraryAccess,
    private val organize: OrganizeMedia,
    /** Syncs the catalog after this screen changed files, so the grid shows the result. */
    private val onLibraryChanged: suspend () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private var source: TimelinePagingSource? = null

    val cells: Flow<PagingData<TimelineCell>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = true, jumpThreshold = PAGE_SIZE * 4)) {
            TimelinePagingSource(getPage).also { source = it }
        }.flow
            .map { it.withDateHeaders(zone) }
            .cachedIn(viewModelScope)

    private val _access = MutableStateFlow(getAccess())
    val access: StateFlow<MediaLibraryAccess> = _access.asStateFlow()

    init {
        viewModelScope.launch { getPage.changes().collect { source?.invalidate() } }
    }

    /** Selected items in the order they were picked; empty means the grid is not in selection mode. */
    private val _selection = MutableStateFlow<Map<String, TimelineItem>>(emptyMap())
    val selection: StateFlow<Map<String, TimelineItem>> = _selection.asStateFlow()

    fun toggle(item: TimelineItem) {
        _selection.value = _selection.value.toggled(item)
    }

    fun setSelection(selection: Map<String, TimelineItem>) {
        _selection.value = selection
    }

    fun clearSelection() {
        _selection.value = emptyMap()
    }

    enum class Operation { MOVE, COPY, RENAME }

    /** What a finished move, copy or rename reports in the Snackbar. */
    data class Outcome(val operation: Operation, val result: FileOperationResult, val count: Int)

    private val _outcomes = Channel<Outcome>(Channel.BUFFERED)
    val outcomes: Flow<Outcome> = _outcomes.receiveAsFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    fun loadFolders() {
        viewModelScope.launch { _folders.value = organize.folders() }
    }

    suspend fun currentName(item: TimelineItem): String? = organize.currentName(item)

    fun move(items: List<TimelineItem>, folder: String) = run(Operation.MOVE, items.size) { organize.move(items, folder) }

    fun copy(items: List<TimelineItem>, folder: String) = run(Operation.COPY, items.size) { organize.copy(items, folder) }

    fun rename(item: TimelineItem, base: String) = run(Operation.RENAME, 1) { organize.rename(item, base) }

    // A failure keeps the selection, so the user can try again or pick another folder.
    private fun run(operation: Operation, count: Int, action: suspend () -> FileOperationResult) {
        viewModelScope.launch {
            val result = action()
            onLibraryChanged()
            if (result == FileOperationResult.DONE) clearSelection()
            _outcomes.send(Outcome(operation, result, count))
        }
    }

    /** Called when the screen resumes, since the user may have changed the grant in system settings. */
    fun refreshAccess() {
        _access.value = getAccess()
    }

    private companion object {
        const val PAGE_SIZE = 120
    }
}
