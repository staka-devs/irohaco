package dev.irohaco.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.irohaco.core.contracts.ModelArtifact
import dev.irohaco.core.contracts.ModelInstallState
import dev.irohaco.core.contracts.ModelInstallState.Failed.Reason
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/**
 * Settings > AI (DESIGN §4.5, M2-02): each model with its state, its size, and what can be done with
 * it. Downloads and imports are checked by SHA-256 before the model becomes usable. A download can be
 * paused and resumed from where it stopped, cancelled, or retried after a failure.
 */
@Composable
internal fun AiSettings(
    models: List<ModelArtifact>,
    states: Map<String, ModelInstallState>,
    importResult: ModelInstallState?,
    onDownload: (String) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    onPause: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
) {
    var deleting by remember { mutableStateOf<ModelArtifact?>(null) }
    deleting?.let { model ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_ai_delete_title, model.displayName)) },
            text = { Text(stringResource(R.string.settings_ai_delete_body)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(model.id) }) { Text(stringResource(R.string.settings_ai_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_ai_cancel)) } },
        )
    }
    val context = LocalContext.current
    Column {
        models.forEach { model ->
            val state = states[model.id] ?: ModelInstallState.NotInstalled
            ListItem(
                headlineContent = { Text(model.displayName) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
                        Text(modelStateLabel(state) + " · " + Formatter.formatShortFileSize(context, model.sizeBytes))
                        val bytes = when (state) {
                            is ModelInstallState.Downloading -> state.downloadedBytes to state.totalBytes
                            is ModelInstallState.Paused -> state.downloadedBytes to state.totalBytes
                            else -> null
                        }
                        bytes?.let { (done, total) ->
                            LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
                modifier = Modifier.padding(horizontal = IrohacoSpacing.l),
            ) {
                when (state) {
                    is ModelInstallState.Installed -> OutlinedButton(onClick = { deleting = model }) { Text(stringResource(R.string.settings_ai_delete)) }
                    is ModelInstallState.Downloading -> {
                        Button(onClick = { onPause(model.id) }) { Text(stringResource(R.string.settings_ai_pause)) }
                        OutlinedButton(onClick = { onCancel(model.id) }) { Text(stringResource(R.string.settings_ai_cancel)) }
                    }
                    is ModelInstallState.Paused -> {
                        Button(onClick = { onDownload(model.id) }) { Text(stringResource(R.string.settings_ai_resume)) }
                        OutlinedButton(onClick = { onCancel(model.id) }) { Text(stringResource(R.string.settings_ai_cancel)) }
                    }
                    ModelInstallState.Verifying -> Unit
                    // A failed download keeps what it got, and the retry resumes from there.
                    is ModelInstallState.Failed -> {
                        Button(onClick = { onDownload(model.id) }) { Text(stringResource(R.string.settings_ai_retry)) }
                        if (state.reason == Reason.DOWNLOAD) {
                            OutlinedButton(onClick = { onCancel(model.id) }) { Text(stringResource(R.string.settings_ai_cancel)) }
                        }
                    }
                    ModelInstallState.NotInstalled -> Button(onClick = { onDownload(model.id) }) { Text(stringResource(R.string.settings_ai_download)) }
                }
            }
        }
        Text(
            stringResource(R.string.settings_ai_download_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.m),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_ai_import)) },
            supportingContent = {
                Text(
                    when (importResult) {
                        is ModelInstallState.Failed -> modelStateLabel(importResult)
                        is ModelInstallState.Installed -> stringResource(R.string.settings_ai_import_done)
                        else -> stringResource(R.string.settings_ai_import_summary)
                    },
                )
            },
            trailingContent = { TextButton(onClick = onImport) { Text(stringResource(R.string.settings_ai_import_choose)) } },
        )
    }
}

/** One line for the category list: the default model's state. */
@Composable
internal fun modelStateLabel(state: ModelInstallState): String = when (state) {
    ModelInstallState.NotInstalled -> stringResource(R.string.settings_ai_not_installed)
    is ModelInstallState.Downloading -> stringResource(
        R.string.settings_ai_downloading,
        if (state.totalBytes > 0) (100 * state.downloadedBytes / state.totalBytes).toInt() else 0,
    )
    is ModelInstallState.Paused -> stringResource(
        R.string.settings_ai_paused,
        if (state.totalBytes > 0) (100 * state.downloadedBytes / state.totalBytes).toInt() else 0,
    )
    ModelInstallState.Verifying -> stringResource(R.string.settings_ai_verifying)
    is ModelInstallState.Installed -> stringResource(R.string.settings_ai_installed)
    is ModelInstallState.Failed -> stringResource(
        when (state.reason) {
            Reason.DOWNLOAD -> R.string.settings_ai_failed_download
            Reason.DIGEST_MISMATCH -> R.string.settings_ai_failed_digest
            Reason.UNKNOWN_MODEL -> R.string.settings_ai_failed_unknown
            Reason.STORAGE -> R.string.settings_ai_failed_storage
        },
    )
}

private val previewModel = ModelArtifact("m", "EmbeddingGemma 2 Text-Vision 440M", "https://example.invalid", 387_710_976, "x")

@IrohacoPreviews
@Composable
private fun AiSettingsDownloadingPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            AiSettings(listOf(previewModel), mapOf("m" to ModelInstallState.Downloading(150_000_000, 387_710_976)), null, {}, {}, {})
        }
    }
}

@IrohacoPreviews
@Composable
private fun AiSettingsPausedPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            AiSettings(listOf(previewModel), mapOf("m" to ModelInstallState.Paused(150_000_000, 387_710_976)), null, {}, {}, {})
        }
    }
}

@IrohacoPreviews
@Composable
private fun AiSettingsInstalledPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            AiSettings(listOf(previewModel), mapOf("m" to ModelInstallState.Installed("/m")), ModelInstallState.Failed(Reason.UNKNOWN_MODEL), {}, {}, {})
        }
    }
}
