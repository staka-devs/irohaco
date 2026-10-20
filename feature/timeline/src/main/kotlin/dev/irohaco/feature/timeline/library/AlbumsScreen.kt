package dev.irohaco.feature.timeline.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.GetTimelinePage
import dev.irohaco.core.application.ManageCollections
import dev.irohaco.core.contracts.Album
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.isValidName
import dev.irohaco.feature.timeline.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumsViewModel(getPage: GetTimelinePage, private val collections: ManageCollections) : ViewModel() {
    val albums: StateFlow<List<Album>> = collections.albums().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The favorites as an album card: count and the first item as cover. */
    val favorites: StateFlow<Album?> = getPage.changes().map {
        val query = MediaList.Favorites.query(TimelineQuery())
        Album(FAVORITES, "", getPage.count(query), getPage(0, 1, query).firstOrNull()?.contentUri)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(title: String) {
        viewModelScope.launch { collections.createAlbum(title) }
    }

    private companion object {
        const val FAVORITES = "favorites"
    }
}

/** The Albums tab: the favorites, then the user's albums; new albums come from the FAB (DESIGN §4.2). */
@Composable
fun AlbumsScreen(viewModel: AlbumsViewModel, onOpen: (MediaList) -> Unit, onOpenSettings: () -> Unit) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    AlbumsContent(favorites, albums, onOpen, onOpenSettings, onCreate = viewModel::create)
}

@Composable
internal fun AlbumsContent(
    favorites: Album?,
    albums: List<Album>,
    onOpen: (MediaList) -> Unit,
    onOpenSettings: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.albums_create),
            label = stringResource(R.string.albums_name),
            onConfirm = {
                creating = false
                onCreate(it)
            },
            onDismiss = { creating = false },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.albums_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(IrohacoIcons.Settings), stringResource(R.string.timeline_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(painterResource(IrohacoIcons.Add), stringResource(R.string.albums_create))
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            contentPadding = PaddingValues(
                start = IrohacoSpacing.l,
                end = IrohacoSpacing.l,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 88.dp, // clear of the FAB
            ),
            horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.l),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "favorites") {
                AlbumCard(stringResource(R.string.albums_favorites), favorites?.itemCount ?: 0, favorites?.coverUri) { onOpen(MediaList.Favorites) }
            }
            items(albums, key = { it.albumId }) { album ->
                AlbumCard(album.title, album.itemCount, album.coverUri) { onOpen(MediaList.Album(album.albumId)) }
            }
        }
    }
}

@Composable
private fun AlbumCard(title: String, count: Int, coverUri: String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
        MediaThumbnail(model = coverUri, contentDescription = "", selected = false, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            pluralStringResource(R.plurals.library_items, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Asks for one name, such as a new album's or a tag's (no "/" and not blank). */
@Composable
internal fun NameDialog(title: String, label: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = isValidName(name)) { Text(stringResource(R.string.albums_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_cancel)) } },
    )
}

@IrohacoPreviews
@Composable
private fun AlbumsPreview() {
    IrohacoTheme(dynamicColor = false) {
        AlbumsContent(
            favorites = Album("favorites", "", 12, null),
            albums = listOf(Album("a1", "Holiday 2026", 48, null), Album("a2", "Family", 0, null)),
            onOpen = {},
            onOpenSettings = {},
            onCreate = {},
        )
    }
}
