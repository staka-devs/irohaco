package dev.irohaco.feature.timeline.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.application.ManageCollections
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.TimelinePagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionViewModel(getPage: GetTimelinePage, collections: ManageCollections, val list: MediaList) : ViewModel() {
    private val query = list.query(getPage.query.value)
    private var source: TimelinePagingSource? = null

    val items: Flow<PagingData<TimelineItem>> =
        Pager(PagingConfig(pageSize = 120, enablePlaceholders = true)) { TimelinePagingSource(getPage, query).also { source = it } }
            .flow.cachedIn(viewModelScope)

    /** The album's or tag's name; null for the favorites, whose name is a string resource. */
    val title: StateFlow<String?> = when (list) {
        is MediaList.Album -> collections.albums().map { albums -> albums.firstOrNull { it.albumId == list.albumId }?.title }
        is MediaList.Tag -> collections.tags().map { tags -> tags.firstOrNull { it.tagId == list.tagId }?.name }
        else -> flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { getPage.changes().collect { source?.invalidate() } }
    }
}

/** An album, a tag or the favorites (PRD GAL-007). */
@Composable
fun CollectionScreen(viewModel: CollectionViewModel, onOpenMedia: (String) -> Unit, onBack: () -> Unit) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val title by viewModel.title.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title ?: if (viewModel.list == MediaList.Favorites) stringResource(R.string.albums_favorites) else "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) }
                },
            )
        },
    ) { padding ->
        if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
            Text(
                stringResource(R.string.albums_empty_list),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(padding).padding(IrohacoSpacing.xxl),
            )
        } else {
            MediaGrid(items, padding, onOpenMedia)
        }
    }
}
