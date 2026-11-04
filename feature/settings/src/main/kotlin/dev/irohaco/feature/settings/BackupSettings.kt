package dev.irohaco.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import dev.irohaco.core.application.ExportBackup
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/** Where the last export stands. */
sealed interface ExportState {
    data object Idle : ExportState
    data object Running : ExportState
    /** Written and read back whole; [records] is how many records it holds. */
    data class Done(val records: Int) : ExportState
    data object Failed : ExportState
}

/** Where a restore stands (UC-RESTORE-01). */
sealed interface RestoreState {
    data object Idle : RestoreState
    data object Opening : RestoreState
    /** Checked and matched; nothing is written until the user agrees. */
    data class Preview(val records: Int, val matched: Int, val needsReview: Int, val missing: Int) : RestoreState
    data object Applying : RestoreState
    data class Done(val added: Int, val skipped: Int) : RestoreState
    /** [unreadable]: a wrong passphrase or a damaged file, as opposed to a restore that was rolled back. */
    data class Failed(val unreadable: Boolean) : RestoreState
}

/**
 * Settings > Library > backup (ADR-0010, UC-BACKUP-01): exports tags, ratings, albums, memos and edits,
 * encrypted with a passphrase, to a file the user picks. The originals, the index, API keys and grants
 * are not in it. [onPick] asks for the file; the passphrase is asked for after that.
 */
@Composable
internal fun BackupSettings(
    state: ExportState,
    /** The file the user picked and the passphrase is now asked for; null otherwise. */
    target: String?,
    onPick: () -> Unit,
    onExport: (String, CharArray) -> Unit,
    onDismiss: () -> Unit,
    restore: RestoreState = RestoreState.Idle,
    /** The backup the user picked to restore; its passphrase is asked for next. */
    restoreSource: String? = null,
    onPickRestore: () -> Unit = {},
    onOpenRestore: (String, CharArray) -> Unit = { _, _ -> },
    onApplyRestore: () -> Unit = {},
    onDismissRestore: () -> Unit = {},
) {
    target?.let { PassphraseDialog(onDismiss) { onExport(it, this) } }
    restoreSource?.let { source -> OpenDialog(onDismissRestore) { onOpenRestore(source, it) } }
    RestoreDialog(restore, onApplyRestore, onDismissRestore)
    if (state is ExportState.Done || state is ExportState.Failed) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(if (state is ExportState.Done) R.string.settings_backup_done else R.string.settings_backup_failed)) },
            text = {
                Text(
                    if (state is ExportState.Done) {
                        stringResource(R.string.settings_backup_done_body, state.records)
                    } else {
                        stringResource(R.string.settings_backup_failed_body)
                    },
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_remote_model_done)) } },
        )
    }
    SectionTitle(R.string.settings_backup)
    Column(
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
        modifier = Modifier.padding(horizontal = IrohacoSpacing.l),
    ) {
        Text(
            stringResource(R.string.settings_backup_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state == ExportState.Running) {
            Text(stringResource(R.string.settings_backup_running), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator()
        } else if (restore == RestoreState.Opening || restore == RestoreState.Applying) {
            Text(stringResource(R.string.settings_restore_running), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Button(onClick = onPick) { Text(stringResource(R.string.settings_backup_export)) }
                OutlinedButton(onClick = onPickRestore) { Text(stringResource(R.string.settings_restore)) }
            }
        }
    }
}

/** The passphrase of the backup to open; a wrong one opens nothing. */
@Composable
private fun OpenDialog(onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_restore_passphrase_title)) },
        text = {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.settings_backup_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = { TextButton(enabled = passphrase.isNotEmpty(), onClick = { onConfirm(passphrase.toCharArray()) }) { Text(stringResource(R.string.settings_restore_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ai_cancel)) } },
    )
}

/** The counts before anything is written (AC-011), then how it went. */
@Composable
private fun RestoreDialog(state: RestoreState, onApply: () -> Unit, onDismiss: () -> Unit) {
    val (title, body) = when (state) {
        is RestoreState.Preview -> R.string.settings_restore_preview_title to
            stringResource(R.string.settings_restore_preview_body, state.records, state.matched, state.needsReview, state.missing)
        is RestoreState.Done -> R.string.settings_restore_done to stringResource(R.string.settings_restore_done_body, state.added, state.skipped)
        is RestoreState.Failed -> R.string.settings_restore_failed to
            stringResource(if (state.unreadable) R.string.settings_restore_unreadable else R.string.settings_restore_rolled_back)
        else -> return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(body) },
        confirmButton = {
            if (state is RestoreState.Preview) {
                TextButton(enabled = state.matched > 0, onClick = onApply) { Text(stringResource(R.string.settings_restore_apply)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_remote_model_done)) }
            }
        },
        dismissButton = if (state is RestoreState.Preview) ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ai_cancel)) } }) else null,
    )
}

/** The passphrase, typed twice; it is not kept anywhere, so losing it loses the backup. */
@Composable
private fun PassphraseDialog(onDismiss: () -> Unit, onConfirm: CharArray.() -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val long = first.length >= ExportBackup.MIN_PASSPHRASE
    val same = first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Text(stringResource(R.string.settings_backup_passphrase_body, ExportBackup.MIN_PASSPHRASE))
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(stringResource(R.string.settings_backup_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text(stringResource(R.string.settings_backup_passphrase_again)) },
                    singleLine = true,
                    isError = second.isNotEmpty() && !same,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = long && same, onClick = { first.toCharArray().onConfirm() }) { Text(stringResource(R.string.settings_backup_export)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ai_cancel)) } },
    )
}

@IrohacoPreviews
@Composable
private fun BackupSettingsPreview() {
    IrohacoTheme {
        Column { BackupSettings(ExportState.Idle, target = null, onPick = {}, onExport = { _, _ -> }, onDismiss = {}) }
    }
}
