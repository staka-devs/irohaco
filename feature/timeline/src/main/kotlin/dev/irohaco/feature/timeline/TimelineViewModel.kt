package dev.irohaco.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.irohaco.core.application.GetLibraryAccess
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.application.MarkMedia
import dev.irohaco.core.application.ManageCollections
import dev.irohaco.core.contracts.Album
import dev.irohaco.core.contracts.Tag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import dev.irohaco.core.application.OrganizeMedia
import dev.irohaco.core.contracts.FileOperationResult
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineSortKey
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

class TimelineViewModel(
    private val getPage: GetTimelinePage,
    private val getAccess: GetLibraryAccess,
    private val organize: OrganizeMedia,
    /** Syncs the catalog after this screen changed files, so the grid shows the result. */
    private val onLibraryChanged: suspend () -> Unit,
    /** Folders hidden from the timeline (GAL-002); the viewer opened from here hides them too. */
    excludedFolders: Flow<Set<FolderRef>> = emptyFlow(),
    private val mark: MarkMedia? = null,
    private val collections: ManageCollections? = null,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private var source: TimelinePagingSource? = null

    val cells: Flow<PagingData<TimelineCell>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = true, jumpThreshold = PAGE_SIZE * 4)) {
            TimelinePagingSource(getPage).also { source = it }
        }.flow
            .map { if (getPage.query.value.sortKey == TimelineSortKey.TAKEN) it.withDateHeaders(zone) else it.withoutHeaders(zone) }
            .cachedIn(viewModelScope)

    /** The order and filter, shared with the viewer (GAL-006). */
    val query: StateFlow<TimelineQuery> = getPage.query.asStateFlow()

    private val _scrollToTop = Channel<Unit>(Channel.CONFLATED)

    /** A new order makes the old position meaningless, so the grid starts again from the top. */
    val scrollToTop: Flow<Unit> = _scrollToTop.receiveAsFlow()

    fun setQuery(query: TimelineQuery) {
        if (query == getPage.query.value) return
        getPage.query.value = query
        source?.invalidate()
        _scrollToTop.trySend(Unit)
        clearSelection() // selected items may no longer be shown
    }

    private val _access = MutableStateFlow(getAccess())
    val access: StateFlow<MediaLibraryAccess> = _access.asStateFlow()

    init {
        viewModelScope.launch { getPage.changes().collect { source?.invalidate() } }
        viewModelScope.launch {
            excludedFolders.collect { excluded ->
                getPage.query.update { it.copy(excludedFolders = excluded) }
                source?.invalidate()
            }
        }
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

    /** Adds the selection to the favorites, or removes it when every item already is one. */
    fun setFavorite(items: List<TimelineItem>, favorite: Boolean) {
        viewModelScope.launch {
            mark?.setFavorite(items, favorite)
            clearSelection()
        }
    }

    val tags: StateFlow<List<Tag>> =
        (collections?.tags() ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<Album>> =
        (collections?.albums() ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTag(items: List<TimelineItem>, name: String) {
        viewModelScope.launch {
            collections?.addTag(items, name)
            clearSelection()
        }
    }

    fun addToAlbum(items: List<TimelineItem>, albumId: String?, newTitle: String? = null) {
        viewModelScope.launch {
            val collections = collections ?: return@launch
            val id = albumId ?: collections.createAlbum(newTitle ?: return@launch)
            collections.addToAlbum(id, items)
            clearSelection()
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
