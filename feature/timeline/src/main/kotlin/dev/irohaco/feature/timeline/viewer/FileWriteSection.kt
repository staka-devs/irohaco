package dev.irohaco.feature.timeline.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.irohaco.core.contracts.FieldEdit
import dev.irohaco.core.contracts.MetadataEdits
import dev.irohaco.core.contracts.MetadataField
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.feature.timeline.R

/** The fields the user edited, in the order of the details sheet. */
internal val MetadataEdits.edited: List<MetadataField>
    get() = listOfNotNull(
        MetadataField.TAKEN.takeIf { takenAtMs != null },
        MetadataField.LOCATION.takeIf { location != null },
        MetadataField.DESCRIPTION.takeIf { description != null },
    )

internal val MetadataField.label: Int
    get() = when (this) {
        MetadataField.TAKEN -> R.string.details_taken
        MetadataField.LOCATION -> R.string.details_location
        MetadataField.DESCRIPTION -> R.string.details_description
    }

/**
 * Taking the edits out of the DB (MET-003): into the file itself for a format that can take them (ADR-0030),
 * or into a copy, which can also leave out the location. A write whose file did not read back as written
 * is shown first, until the user restores the file or keeps it (AC-008).
 */
@Composable
internal fun FileWriteSection(
    edits: MetadataEdits,
    fileWrite: ViewerViewModel.FileWrite,
    writeToCheck: String?,
    locationReadable: Boolean,
    onAllowLocation: () -> Unit,
    onWrite: () -> Unit,
    onSaveCopy: () -> Unit,
    onRestore: (String) -> Unit,
    onKeepFile: (String) -> Unit,
) {
    val note = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
        if (writeToCheck != null) {
            Text(stringResource(R.string.file_write_unchecked), style = note, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Button(onClick = { onRestore(writeToCheck) }) { Text(stringResource(R.string.file_write_restore)) }
                TextButton(onClick = { onKeepFile(writeToCheck) }) { Text(stringResource(R.string.file_write_keep)) }
            }
            return@Column
        }
        if (edits.edited.isNotEmpty()) {
            when {
                !fileWrite.toFile -> Text(stringResource(R.string.file_write_unsupported), style = note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                !locationReadable -> {
                    Text(stringResource(R.string.file_write_needs_location), style = note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onAllowLocation) { Text(stringResource(R.string.details_allow_location)) }
                }
                else -> Button(onClick = onWrite) { Text(stringResource(R.string.file_write)) }
            }
        }
        if (fileWrite.toCopy) OutlinedButton(onClick = onSaveCopy) { Text(stringResource(R.string.file_save_copy)) }
    }
}

/** Picks the edited fields to write; a field in conflict with the file is settled first (INT-002). */
@Composable
internal fun WriteToFileDialog(edits: MetadataEdits, onDismiss: () -> Unit, onWrite: (Set<MetadataField>) -> Unit) {
    val writable = edits.edited.filter { it !in edits.conflicts }
    var chosen by rememberSaveable { mutableStateOf(writable.map { it.name }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_write)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Text(stringResource(R.string.file_write_explain))
                edits.edited.forEach { field ->
                    val enabled = field in writable
                    CheckRow(
                        label = stringResource(field.label),
                        checked = field.name in chosen,
                        enabled = enabled,
                        onChange = { chosen = if (it) chosen + field.name else chosen - field.name },
                    )
                    if (!enabled) Text(stringResource(R.string.file_write_conflict), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onWrite(chosen.map(MetadataField::valueOf).toSet()) }, enabled = chosen.isNotEmpty()) {
                Text(stringResource(R.string.file_write_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_cancel)) } },
    )
}

/** A copy beside the original with the edits written in, and without the location if the user asks (MET-003). */
@Composable
internal fun SaveCopyDialog(onDismiss: () -> Unit, onSave: (removeLocation: Boolean) -> Unit) {
    var removeLocation by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_save_copy)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Text(stringResource(R.string.file_save_copy_explain))
                CheckRow(stringResource(R.string.file_save_copy_remove_location), removeLocation, enabled = true) { removeLocation = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(removeLocation) }) { Text(stringResource(R.string.memo_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_cancel)) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}

@IrohacoPreviews
@Composable
private fun FileWriteSectionPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.l)) {
                val edits = MetadataEdits(description = FieldEdit.Set("Harbour at dawn"))
                FileWriteSection(edits, ViewerViewModel.FileWrite(toFile = true, toCopy = true), null, true, {}, {}, {}, {}, {})
                FileWriteSection(edits, ViewerViewModel.FileWrite(toCopy = true), null, true, {}, {}, {}, {}, {})
                FileWriteSection(edits, ViewerViewModel.FileWrite(toFile = true, toCopy = true), "op", true, {}, {}, {}, {}, {})
            }
        }
    }
}
