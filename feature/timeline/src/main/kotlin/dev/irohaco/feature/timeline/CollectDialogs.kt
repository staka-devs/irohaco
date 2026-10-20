package dev.irohaco.feature.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.irohaco.core.contracts.Album
import dev.irohaco.core.contracts.Tag
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.isValidName

/** A tag name to add; existing tags that match what is typed are offered as chips (GAL-007). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagDialog(tags: List<Tag>, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val suggestions = tags.filter { it.name.contains(name.trim(), ignoreCase = true) }.take(12)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text(stringResource(R.string.tags_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                    suggestions.forEach { tag -> SuggestionChip(onClick = { onAdd(tag.name) }, label = { Text(tag.name) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name.trim()) }, enabled = isValidName(name)) { Text(stringResource(R.string.tags_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_cancel)) } },
    )
}

/** Picks an album for the selection, or names a new one (GAL-007). */
@Composable
internal fun AlbumPickerDialog(albums: List<Album>, onPick: (String) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.albums_add_to)) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(albums, key = { it.albumId }) { album ->
                        ListItem(
                            headlineContent = { Text(album.title) },
                            leadingContent = { Icon(painterResource(IrohacoIcons.PhotoAlbum), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.clickable { onPick(album.albumId) },
                        )
                    }
                }
                OutlinedTextField(
                    title, { title = it },
                    label = { Text(stringResource(R.string.albums_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title.trim()) }, enabled = isValidName(title)) { Text(stringResource(R.string.albums_create_and_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_cancel)) } },
    )
}
