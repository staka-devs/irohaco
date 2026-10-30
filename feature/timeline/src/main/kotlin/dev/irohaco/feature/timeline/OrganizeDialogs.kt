package dev.irohaco.feature.timeline

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.domain.baseName
import dev.irohaco.core.domain.isValidName
import dev.irohaco.core.domain.newFolderPath

/** Where to move or copy the selection: a folder in the library, or a new one under Pictures/ (GAL-005). */
@Composable
internal fun FolderDialog(title: String, folders: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var newFolder by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (folders.isEmpty()) {
                    Text(stringResource(R.string.timeline_no_folders), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(folders) { folder ->
                        ListItem(
                            leadingContent = { Icon(painterResource(IrohacoIcons.Folder), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.clickable { onPick(folder) },
                        ) { Text(folder) }
                    }
                }
                OutlinedTextField(
                    value = newFolder,
                    onValueChange = { newFolder = it },
                    label = { Text(stringResource(R.string.timeline_new_folder)) },
                    supportingText = { Text(stringResource(R.string.timeline_new_folder_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(newFolderPath(newFolder)) }, enabled = isValidName(newFolder)) {
                Text(stringResource(R.string.timeline_create_folder))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_cancel)) } },
    )
}

/** The new name without the extension, which the file keeps (GAL-005). */
@Composable
internal fun RenameDialog(currentName: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var base by rememberSaveable(currentName) { mutableStateOf(baseName(currentName)) }
    val extension = currentName.removePrefix(baseName(currentName))
    val valid = isValidName(base)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timeline_rename)) },
        text = {
            OutlinedTextField(
                value = base,
                onValueChange = { base = it },
                label = { Text(stringResource(R.string.timeline_rename_name)) },
                suffix = { Text(extension) },
                isError = !valid,
                supportingText = { if (!valid) Text(stringResource(R.string.timeline_invalid_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(base) }, enabled = valid) { Text(stringResource(R.string.timeline_rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_cancel)) } },
    )
}
