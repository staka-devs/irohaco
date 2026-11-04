package dev.irohaco.feature.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.irohaco.core.contracts.Album
import dev.irohaco.core.contracts.Tag
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.isValidName
import java.text.Normalizer

/**
 * Looks a tag up by what is typed, or names a new one (GAL-007). Matching tags come best first, so a long
 * list stays one tap away; the button makes a new tag only when no tag has that name.
 */
@Composable
internal fun TagDialog(tags: List<Tag>, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val matches = remember(tags, name) { matchingTags(tags, name) }
    val same = matches.firstOrNull()?.takeIf { it.name.foldForSearch() == name.foldForSearch() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text(stringResource(R.string.tags_search)) },
                    leadingIcon = { Icon(painterResource(IrohacoIcons.Search), contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tags.isEmpty()) {
                    Text(stringResource(R.string.tags_none), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(matches, key = { it.tagId }) { tag ->
                        ListItem(
                            leadingContent = { Icon(painterResource(IrohacoIcons.Label), contentDescription = null) },
                            supportingContent = { Text(pluralStringResource(R.plurals.tags_count, tag.itemCount, tag.itemCount)) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.clickable { onAdd(tag.name) },
                        ) { Text(tag.name) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(same?.name ?: name.trim()) }, enabled = isValidName(name)) {
                Text(if (same != null) stringResource(R.string.tags_add) else stringResource(R.string.tags_create_and_add, name.trim()))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_cancel)) } },
    )
}

/** Tags that match [query]: the same name, then names that start with it, then the rest; the most used first in each. */
internal fun matchingTags(tags: List<Tag>, query: String): List<Tag> {
    val wanted = query.foldForSearch()
    return tags.mapNotNull { tag ->
        val name = tag.name.foldForSearch()
        when {
            wanted.isEmpty() -> 2
            name == wanted -> 0
            name.startsWith(wanted) -> 1
            wanted in name -> 2
            else -> null
        }?.let { rank -> rank to tag }
    }.sortedWith(compareBy<Pair<Int, Tag>> { it.first }.thenByDescending { it.second.itemCount }.thenBy { it.second.name.foldForSearch() })
        .map { it.second }
}

/** Width, case, and hiragana against katakana do not matter when looking a tag up ("ﾀｸﾞ", "タグ", "たぐ"). */
internal fun String.foldForSearch(): String =
    Normalizer.normalize(trim(), Normalizer.Form.NFKC).lowercase().map { if (it in '\u3041'..'\u3096') it + 0x60 else it }.joinToString("")

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
                            leadingContent = { Icon(painterResource(IrohacoIcons.PhotoAlbum), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.clickable { onPick(album.albumId) },
                        ) { Text(album.title) }
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
