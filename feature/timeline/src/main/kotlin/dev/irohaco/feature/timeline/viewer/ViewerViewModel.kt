package dev.irohaco.feature.timeline.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.feature.timeline.TimelinePagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.max

/** Opens the timeline at one asset and pages through its neighbours in timeline order. */
class ViewerViewModel(private val getPage: GetTimelinePage, assetId: String) : ViewModel() {
    sealed interface Start {
        data object Loading : Start
        data object NotFound : Start
        data class At(val index: Int) : Start
    }

    private val _start = MutableStateFlow<Start>(Start.Loading)
    val start: StateFlow<Start> = _start.asStateFlow()

    val items: Flow<PagingData<TimelineItem>> = flow {
        val index = (_start.first { it is Start.At } as Start.At).index
        emitAll(
            Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = true), initialKey = max(0, index - PAGE_SIZE / 2)) {
                TimelinePagingSource(getPage)
            }.flow,
        )
    }.cachedIn(viewModelScope)

    init {
        viewModelScope.launch { _start.value = getPage.indexOf(assetId)?.let { Start.At(it) } ?: Start.NotFound }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
