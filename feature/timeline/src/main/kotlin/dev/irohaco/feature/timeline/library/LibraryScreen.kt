package dev.irohaco.feature.timeline.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.BrowseLibrary
import dev.irohaco.core.application.ManageCollections
import dev.irohaco.core.contracts.Tag
import kotlinx.coroutines.flow.flowOf
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.designsystem.component.BadgeStatus
import dev.irohaco.core.designsystem.component.StatusBadge
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.feature.timeline.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(browse: BrowseLibrary, collections: ManageCollections? = null) : ViewModel() {
    val tags: StateFlow<List<Tag>> =
        (collections?.tags() ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Null until the first read, so the screen does not flash "no folders". */
    val folders: StateFlow<List<LibraryFolder>?> =
        browse.folders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val excluded: StateFlow<Set<FolderRef>> =
        browse.excludedFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}

/** The Library tab: folders per volume (PRD GAL-002, DESIGN §4.4). */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenFolder: (FolderRef) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val excluded by viewModel.excluded.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    LibraryContent(folders, excluded, onOpenFolder, onOpenSettings, tags, onOpenTag)
}

@Composable
internal fun LibraryContent(
    folders: List<LibraryFolder>?,
    excluded: Set<FolderRef>,
    onOpenFolder: (FolderRef) -> Unit,
    onOpenSettings: () -> Unit,
    tags: List<Tag> = emptyList(),
    onOpenTag: (String) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(IrohacoIcons.Settings), stringResource(R.string.timeline_settings))
                    }
                },
            )
        },
    ) { padding ->
        if (folders?.isEmpty() == true) {
            Text(
                stringResource(R.string.library_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(padding).padding(IrohacoSpacing.xxl),
            )
            return@Scaffold
        }
        val volumes = folders.orEmpty().groupBy { it.folder.volumeId }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            for ((volumeId, volumeFolders) in volumes) {
                val first = volumeFolders.first()
                item(key = "volume-$volumeId") { VolumeHeader(first.volumeName, first.offline) }
                items(folderTree(volumeFolders), key = { "$volumeId/${it.path}" }) { line ->
                    FolderItem(line, excluded = line.folder?.folder in excluded, onOpen = onOpenFolder)
                }
            }
            if (tags.isNotEmpty()) {
                item(key = "tags") {
                    Text(
                        stringResource(R.string.library_tags),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
                    )
                }
                items(tags, key = { "tag-${it.tagId}" }) { tag ->
                    ListItem(
                        headlineContent = { Text(tag.name) },
                        supportingContent = { Text(pluralStringResource(R.plurals.library_items, tag.itemCount, tag.itemCount)) },
                        leadingContent = { Icon(painterResource(IrohacoIcons.Label), contentDescription = null) },
                        modifier = Modifier.clickable { onOpenTag(tag.tagId) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun volumeLabel(volumeName: String): String =
    if (volumeName == PRIMARY_VOLUME) stringResource(R.string.library_internal) else stringResource(R.string.library_sd_card, volumeName)

private const val PRIMARY_VOLUME = "external_primary"

@Composable
private fun VolumeHeader(volumeName: String, offline: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(volumeLabel(volumeName), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        // Offline is a label and an icon, never a greyed-out row (DESIGN §4.4).
        if (offline) StatusBadge(BadgeStatus.Offline)
    }
}

@Composable
private fun FolderItem(line: FolderLine, excluded: Boolean, onOpen: (FolderRef) -> Unit) {
    val folder = line.folder
    ListItem(
        headlineContent = { Text(line.name.ifEmpty { stringResource(R.string.library_root) }) },
        supportingContent = folder?.let {
            {
                val count = pluralStringResource(R.plurals.library_items, it.itemCount, it.itemCount)
                Text(if (excluded) stringResource(R.string.library_excluded, count) else count)
            }
        },
        leadingContent = {
            Icon(painterResource(if (excluded) IrohacoIcons.VisibilityOff else IrohacoIcons.Folder), contentDescription = null)
        },
        modifier = Modifier
            .padding(start = IrohacoSpacing.l * (line.depth - 1).coerceAtLeast(0))
            .then(if (folder != null) Modifier.clickable { onOpen(folder.folder) } else Modifier),
    )
}

@IrohacoPreviews
@Composable
private fun LibraryPreview() {
    IrohacoTheme(dynamicColor = false) {
        LibraryContent(
            folders = listOf(
                LibraryFolder(FolderRef("sd", "DCIM/"), "1234-5678", offline = true, itemCount = 120),
                LibraryFolder(FolderRef("in", ""), "external_primary", offline = false, itemCount = 2),
                LibraryFolder(FolderRef("in", "DCIM/Camera/"), "external_primary", offline = false, itemCount = 1_204),
                LibraryFolder(FolderRef("in", "Pictures/Screenshots/"), "external_primary", offline = false, itemCount = 37),
            ),
            excluded = setOf(FolderRef("in", "Pictures/Screenshots/")),
            onOpenFolder = {},
            onOpenSettings = {},
            tags = listOf(Tag("t1", "Trip", 24)),
        )
    }
}
