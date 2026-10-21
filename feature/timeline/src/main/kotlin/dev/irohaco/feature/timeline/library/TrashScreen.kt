package dev.irohaco.feature.timeline.library

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.BrowseTrash
import dev.irohaco.core.contracts.TrashedItem
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.trashRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrashViewModel(
    private val browse: BrowseTrash,
    /** Syncs the catalog after a restore or a delete, so the rest of the app reflects it (GAL-015). */
    private val onChanged: suspend () -> Unit = {},
) : ViewModel() {
    private val _items = MutableStateFlow<List<TrashedItem>>(emptyList())
    val items: StateFlow<List<TrashedItem>> = _items.asStateFlow()

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads MediaStore's trash; the caller does this after restoring or deleting (GAL-015). */
    fun refresh() {
        viewModelScope.launch { _items.value = browse.list() }
    }

    fun toggle(id: Long) {
        _selection.value = if (id in _selection.value) _selection.value - id else _selection.value + id
    }

    fun selectAll() {
        _selection.value = _items.value.map { it.mediaStoreId }.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** The system confirmed a restore or a delete; syncs the catalog and drops it from this list. */
    fun changed() {
        clearSelection()
        viewModelScope.launch { onChanged(); refresh() }
    }
}

/** The trash: MediaStore's own, so another app's deletions show up too (PRD GAL-015). */
@Composable
fun TrashScreen(viewModel: TrashViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) viewModel.changed()
    }
    val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) viewModel.changed()
    }
    val selected = items.filter { it.mediaStoreId in selection }
    TrashContent(
        items = items,
        selection = selection,
        onBack = onBack,
        onToggle = viewModel::toggle,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onRestore = { restore.launch(trashRequest(context, selected.map { Uri.parse(it.contentUri) }, trashed = false)) },
        onDeleteForever = { delete.launch(deleteForeverRequest(context, selected.map { Uri.parse(it.contentUri) })) },
    )
}

/** The system dialog that permanently deletes files (GAL-015); the asset's own DB rows stay (M1-09). */
private fun deleteForeverRequest(context: Context, uris: List<Uri>): IntentSenderRequest =
    IntentSenderRequest.Builder(MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender).build()

@Composable
internal fun TrashContent(
    items: List<TrashedItem>,
    selection: Set<Long> = emptySet(),
    onBack: () -> Unit = {},
    onToggle: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onRestore: () -> Unit = {},
    onDeleteForever: () -> Unit = {},
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.trash_delete_confirm_title)) },
            text = { Text(stringResource(R.string.trash_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDeleteForever() }) { Text(stringResource(R.string.trash_delete_forever)) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.timeline_cancel)) } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selection.isEmpty()) {
                            stringResource(R.string.trash_title)
                        } else {
                            pluralStringResource(R.plurals.trash_selected, selection.size, selection.size)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = if (selection.isEmpty()) onBack else onClearSelection) {
                        Icon(
                            painterResource(if (selection.isEmpty()) IrohacoIcons.ArrowBack else IrohacoIcons.Close),
                            stringResource(if (selection.isEmpty()) R.string.viewer_back else R.string.timeline_clear_selection),
                        )
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = onSelectAll) {
                            Icon(painterResource(IrohacoIcons.Check), stringResource(R.string.trash_select_all))
                        }
                    }
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = onRestore) {
                            Icon(painterResource(IrohacoIcons.Restore), stringResource(R.string.trash_restore))
                        }
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(painterResource(IrohacoIcons.DeleteForever), stringResource(R.string.trash_delete_forever))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.trash_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(padding).padding(IrohacoSpacing.xxl),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(items, key = { it.mediaStoreId }) { item ->
                TrashRow(item, selected = item.mediaStoreId in selection, onClick = { onToggle(item.mediaStoreId) })
            }
        }
    }
}

@Composable
private fun TrashRow(item: TrashedItem, selected: Boolean, onClick: () -> Unit) {
    val days = daysRemaining(item.expiresAtMs)
    ListItem(
        headlineContent = { Text(item.displayName, maxLines = 1) },
        supportingContent = {
            Text(
                if (days != null) pluralStringResource(R.plurals.trash_days_remaining, days, days) else stringResource(R.string.trash_days_unknown),
            )
        },
        leadingContent = {
            Box(Modifier.size(48.dp)) { MediaThumbnail(model = item.contentUri, contentDescription = "", selected = false) }
        },
        trailingContent = { Checkbox(checked = selected, onCheckedChange = { onClick() }) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Days left before MediaStore removes the item for good; null when it reports no expiry. */
internal fun daysRemaining(expiresAtMs: Long?): Int? =
    expiresAtMs?.let { ((it - System.currentTimeMillis()) / 86_400_000L).toInt().coerceAtLeast(0) }

@IrohacoPreviews
@Composable
private fun TrashPreview() {
    IrohacoTheme(dynamicColor = false) {
        TrashContent(
            items = listOf(
                TrashedItem(1, "content://1", MediaKind.IMAGE, "IMG_001.jpg", System.currentTimeMillis() + 15 * 86_400_000L),
                TrashedItem(2, "content://2", MediaKind.VIDEO, "VID_002.mp4", System.currentTimeMillis() + 2 * 86_400_000L),
            ),
            selection = setOf(2L),
        )
    }
}

@IrohacoPreviews
@Composable
private fun TrashEmptyPreview() {
    IrohacoTheme(dynamicColor = false) { TrashContent(items = emptyList()) }
}
