package dev.irohaco.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.irohaco.core.application.GetLibraryAccess
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId

class TimelineViewModel(
    getPage: GetTimelinePage,
    private val getAccess: GetLibraryAccess,
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

    /** Called when the screen resumes, since the user may have changed the grant in system settings. */
    fun refreshAccess() {
        _access.value = getAccess()
    }

    private companion object {
        const val PAGE_SIZE = 120
    }
}
