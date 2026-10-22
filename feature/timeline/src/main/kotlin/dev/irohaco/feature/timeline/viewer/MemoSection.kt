package dev.irohaco.feature.timeline.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.irohaco.core.contracts.Annotation
import dev.irohaco.core.contracts.AnnotationAuthor
import dev.irohaco.core.contracts.AnnotationKind
import dev.irohaco.core.contracts.AnnotationRevision
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.feature.timeline.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The search memos of the shown photo or video (ANN-001〜003): each with its kind, author and whether
 * search uses it; added, edited (as a new revision), looked back on and deleted here.
 */
@Composable
internal fun MemoSection(
    memos: List<Annotation>,
    onAdd: (AnnotationKind, String) -> Unit,
    onEdit: (String, String) -> Unit,
    onSearchable: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    history: suspend (String) -> List<AnnotationRevision>,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var showingHistory by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    if (adding) MemoEditor(null, null, onDismiss = { adding = false }) { kind, text -> adding = false; onAdd(kind, text) }
    editing?.let { id ->
        val memo = memos.firstOrNull { it.annotationId == id }
        if (memo == null) editing = null else MemoEditor(memo.kind, memo.current.text, onDismiss = { editing = null }) { _, text -> editing = null; onEdit(id, text) }
    }
    showingHistory?.let { id -> MemoHistory(id, history) { showingHistory = null } }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.memo_delete_title)) },
            text = { Text(stringResource(R.string.memo_delete_body)) },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(id) }) { Text(stringResource(R.string.memo_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.memo_cancel)) } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
        Text(stringResource(R.string.memo_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        memos.forEach { memo ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(IrohacoSpacing.m), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
                    Text(
                        listOfNotNull(stringResource(memo.kind.label()), memo.current.author.takeIf { it == AnnotationAuthor.AI }?.let { stringResource(R.string.memo_by_ai) })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(memo.current.text, style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                        val searchLabel = stringResource(R.string.memo_searchable)
                        Switch(
                            checked = memo.searchable,
                            onCheckedChange = { onSearchable(memo.annotationId, it) },
                            modifier = Modifier.semantics { contentDescription = searchLabel },
                        )
                        Text(searchLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
                        TextButton(onClick = { editing = memo.annotationId }) { Text(stringResource(R.string.memo_edit)) }
                        if (memo.current.revisionNo > 1) TextButton(onClick = { showingHistory = memo.annotationId }) { Text(stringResource(R.string.memo_history)) }
                        TextButton(onClick = { deleting = memo.annotationId }) { Text(stringResource(R.string.memo_delete)) }
                    }
                }
            }
        }
        TextButton(onClick = { adding = true }) { Text(stringResource(R.string.memo_add)) }
    }
}

/** Writes a new memo (with its kind) or the next revision of one; an empty text cannot be saved. */
@Composable
private fun MemoEditor(kind: AnnotationKind?, text: String?, onDismiss: () -> Unit, onSave: (AnnotationKind, String) -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(kind ?: AnnotationKind.CONTEXT) }
    var value by rememberSaveable { mutableStateOf(text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (text == null) R.string.memo_add else R.string.memo_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                // The kind is chosen once; an edit changes the text only.
                if (kind == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
                        USER_KINDS.forEach { FilterChip(chosen == it, { chosen = it }, label = { Text(stringResource(it.label())) }) }
                    }
                }
                OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.memo_text)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen, value) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.memo_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_cancel)) } },
    )
}

/** Every revision, newest first, with who wrote it and when (ANN-003). */
@Composable
private fun MemoHistory(annotationId: String, history: suspend (String) -> List<AnnotationRevision>, onDismiss: () -> Unit) {
    val revisions by produceState(emptyList<AnnotationRevision>(), annotationId) { value = history(annotationId) }
    val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memo_history)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m)) {
                revisions.forEach { revision ->
                    val time = formatter.format(Instant.ofEpochMilli(revision.modifiedAt).atZone(ZoneId.systemDefault()))
                    val author = if (revision.author == AnnotationAuthor.AI) stringResource(R.string.memo_by_ai_model, revision.modelProfileId.orEmpty()) else stringResource(R.string.memo_by_user)
                    Column {
                        Text(stringResource(R.string.memo_revision, revision.revisionNo, time, author), style = MaterialTheme.typography.labelMedium)
                        Text(revision.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_close)) } },
    )
}

private val USER_KINDS = listOf(AnnotationKind.CONTEXT, AnnotationKind.VISUAL_DESCRIPTION, AnnotationKind.KEYWORDS)

private fun AnnotationKind.label() = when (this) {
    AnnotationKind.CONTEXT -> R.string.memo_kind_context
    AnnotationKind.VISUAL_DESCRIPTION -> R.string.memo_kind_visual
    AnnotationKind.KEYWORDS -> R.string.memo_kind_keywords
    AnnotationKind.AI_CAPTION -> R.string.memo_kind_ai_caption
}

@IrohacoPreviews
@Composable
private fun MemoSectionPreview() {
    val user = AnnotationRevision(2, AnnotationAuthor.USER, null, "祖母の家の庭。毎年この時期に梅が咲く", 0)
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(IrohacoSpacing.l)) {
                MemoSection(
                    memos = listOf(
                        Annotation("a", AnnotationKind.CONTEXT, true, 0, user),
                        Annotation("b", AnnotationKind.AI_CAPTION, false, 0, AnnotationRevision(1, AnnotationAuthor.AI, "m", "白い梅の花と古い木の塀", 0)),
                    ),
                    onAdd = { _, _ -> }, onEdit = { _, _ -> }, onSearchable = { _, _ -> }, onDelete = {}, history = { emptyList() },
                )
            }
        }
    }
}
