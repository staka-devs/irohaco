package dev.irohaco.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.irohaco.core.application.ImageSpaces
import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.IndexJobProgress
import dev.irohaco.core.contracts.IndexJobState
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.contracts.PauseReason
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/** The job requested last, as the indexing section shows it. */
data class IndexingUi(val jobId: String, val progress: IndexJobProgress, val failedNames: List<String>, val pauseReason: PauseReason?)

/**
 * Settings > AI > index (DESIGN §4.5, §4.7, IDX-001, IDX-002): starts an image index of every photo or
 * of chosen folders, shows its progress, and pauses, resumes, cancels and retries it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IndexingSettings(
    indexing: IndexingUi?,
    modelReady: Boolean,
    folders: List<LibraryFolder>,
    onStart: (Set<String>?) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    // Wrapped so that "every folder" (null) differs from "nothing to confirm".
    var confirming by remember { mutableStateOf<Target?>(null) }
    var choosing by remember { mutableStateOf(false) }
    // The confirmation comes before the first index only (DESIGN §3).
    val start = { chosen: Set<String>? ->
        if (indexing == null) {
            confirming = Target(chosen)
        } else {
            onStart(chosen)
        }
    }
    confirming?.let { target ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.settings_index_confirm_title)) },
            text = { Text(stringResource(R.string.settings_index_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirming = null; onStart(target.folders) }) { Text(stringResource(R.string.settings_index_start)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.settings_ai_cancel)) } },
        )
    }
    if (choosing) FolderChooser(folders, onDismiss = { choosing = false }, onChoose = { choosing = false; start(it) })

    Column(
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
        modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.m),
    ) {
        Text(stringResource(R.string.settings_index), style = MaterialTheme.typography.titleMedium)
        val active = indexing?.progress?.state in ACTIVE
        if (indexing != null) JobProgress(indexing, onPause, onResume, onCancel, onRetry)
        if (!active) {
            if (!modelReady) {
                Text(stringResource(R.string.settings_index_needs_model), style = MaterialTheme.typography.bodyMedium)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                SplitButtonLayout(
                    leadingButton = {
                        SplitButtonDefaults.LeadingButton(onClick = { start(null) }, enabled = modelReady) {
                            Text(stringResource(R.string.settings_index_start))
                        }
                    },
                    trailingButton = {
                        SplitButtonDefaults.TrailingButton(checked = menu, onCheckedChange = { menu = it }, enabled = modelReady) {
                            Icon(painterResource(IrohacoIcons.ExpandMore), stringResource(R.string.settings_index_choose_target))
                        }
                    },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_index_all)) }, onClick = { menu = false; start(null) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_index_folders)) }, onClick = { menu = false; choosing = true })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JobProgress(
    indexing: IndexingUi,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val p = indexing.progress
    val done = p.committed + p.failed + p.skipped
    val percent = if (p.total > 0) 100 * done / p.total else 0
    Text(stateLabel(p.state, indexing.pauseReason), style = MaterialTheme.typography.bodyLarge)
    if (p.state in ACTIVE) {
        LinearWavyProgressIndicator(progress = { if (p.total > 0) done.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
    }
    Text(stringResource(R.string.settings_index_counts, done, p.total, percent), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.settings_index_remaining, p.pending, p.failed), style = MaterialTheme.typography.bodyMedium)
    // Each photo keeps one vector of 4-byte values; the database adds little on top.
    val bytes = p.total.toLong() * ImageSpaces.LOCAL.dimension * 4
    Text(
        stringResource(R.string.settings_index_storage, Formatter.formatShortFileSize(LocalContext.current, bytes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        when (p.state) {
            IndexJobState.PLANNED, IndexJobState.RUNNING -> Button(onClick = { onPause(indexing.jobId) }) { Text(stringResource(R.string.settings_index_pause)) }
            IndexJobState.PAUSED -> Button(onClick = { onResume(indexing.jobId) }) { Text(stringResource(R.string.settings_index_resume)) }
            else -> Unit
        }
        if (p.state in ACTIVE) OutlinedButton(onClick = { onCancel(indexing.jobId) }) { Text(stringResource(R.string.settings_ai_cancel)) }
    }
    if (indexing.failedNames.isNotEmpty()) {
        Text(stringResource(R.string.settings_index_failed_list), style = MaterialTheme.typography.titleSmall)
        indexing.failedNames.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (p.state == IndexJobState.PARTIAL || p.state == IndexJobState.FAILED) {
            OutlinedButton(onClick = { onRetry(indexing.jobId) }) { Text(stringResource(R.string.settings_index_retry)) }
        }
    }
}

@Composable
private fun stateLabel(state: IndexJobState, reason: PauseReason?): String = stringResource(
    when (state) {
        IndexJobState.PLANNED, IndexJobState.RUNNING, IndexJobState.VERIFYING -> R.string.settings_index_running
        IndexJobState.PAUSING -> R.string.settings_index_pausing
        IndexJobState.PAUSED -> when (reason) {
            PauseReason.THERMAL -> R.string.settings_index_paused_thermal
            PauseReason.BATTERY -> R.string.settings_index_paused_battery
            null -> R.string.settings_index_paused
        }
        IndexJobState.SUCCEEDED -> R.string.settings_index_succeeded
        IndexJobState.PARTIAL -> R.string.settings_index_partial
        IndexJobState.FAILED -> R.string.settings_index_failed
        IndexJobState.CANCELLED -> R.string.settings_index_cancelled
    },
)

/** Picks the folders to index; a folder is its relative path, as the index plans it. */
@Composable
private fun FolderChooser(folders: List<LibraryFolder>, onDismiss: () -> Unit, onChoose: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    val paths = folders.map { it.folder.relativePath }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_index_folders)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                paths.forEach { path ->
                    val checked = path in chosen
                    ListItem(
                        headlineContent = { Text(path.ifEmpty { "/" }) },
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        modifier = Modifier.toggleable(checked, role = Role.Checkbox) { chosen = if (it) chosen + path else chosen - path },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(chosen) }, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.settings_index_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ai_cancel)) } },
    )
}

private data class Target(val folders: Set<String>?)

private val ACTIVE = setOf(IndexJobState.PLANNED, IndexJobState.RUNNING, IndexJobState.PAUSING, IndexJobState.PAUSED, IndexJobState.VERIFYING)

private val previewFolders = listOf(LibraryFolder(FolderRef("v", "DCIM/Camera/"), "external_primary", false, 120))

@IrohacoPreviews
@Composable
private fun IndexingRunningPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            IndexingSettings(
                IndexingUi("j", IndexJobProgress(IndexJobState.RUNNING, 580, 400, 20, 0), listOf("IMG_0001.jpg"), null),
                modelReady = true, folders = previewFolders, onStart = {}, onPause = {}, onResume = {}, onCancel = {}, onRetry = {},
            )
        }
    }
}

@IrohacoPreviews
@Composable
private fun IndexingPartialPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            IndexingSettings(
                IndexingUi("j", IndexJobProgress(IndexJobState.PARTIAL, 0, 998, 2, 0), listOf("IMG_0001.jpg", "IMG_0002.heic"), null),
                modelReady = true, folders = previewFolders, onStart = {}, onPause = {}, onResume = {}, onCancel = {}, onRetry = {},
            )
        }
    }
}

@IrohacoPreviews
@Composable
private fun IndexingNoModelPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            IndexingSettings(null, modelReady = false, folders = previewFolders, onStart = {}, onPause = {}, onResume = {}, onCancel = {}, onRetry = {})
        }
    }
}
