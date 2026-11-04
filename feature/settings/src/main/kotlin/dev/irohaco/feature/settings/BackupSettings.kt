package dev.irohaco.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
) {
    target?.let { PassphraseDialog(onDismiss) { onExport(it, this) } }
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
        } else {
            Button(onClick = onPick) { Text(stringResource(R.string.settings_backup_export)) }
        }
    }
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
