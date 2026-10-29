package dev.irohaco.feature.timeline.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import dev.irohaco.core.application.AskResult
import dev.irohaco.core.application.Basis
import dev.irohaco.core.application.Citation
import dev.irohaco.core.application.Evidence
import dev.irohaco.core.application.SearchHit
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.component.GridThumbnail
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.formatDuration

/**
 * Asking about the search results (RAG-001〜003): the answer, and under it each photo or scene it rests on,
 * marked as seen in it, written in the user's memo, or guessed. Tapping one opens it at its scene.
 */
@Composable
internal fun AnswerCard(asking: Boolean, answer: AskResult?, onAsk: () -> Unit, onOpen: (SearchHit) -> Unit, onOpenSettings: () -> Unit) {
    if (answer == null && !asking) {
        OutlinedButton(onClick = onAsk) { Text(stringResource(R.string.search_ask)) }
        return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
            when (answer) {
                null -> {
                    Text(stringResource(R.string.search_asking), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                AskResult.NoModel -> {
                    Text(stringResource(R.string.search_ask_no_model), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.search_open_settings)) }
                }
                AskResult.NothingFound -> Text(stringResource(R.string.search_ask_nothing), style = MaterialTheme.typography.bodyMedium)
                is AskResult.Failed -> {
                    Text(stringResource(R.string.search_ask_failed), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onAsk) { Text(stringResource(R.string.search_ask_retry)) }
                }
                is AskResult.Answered -> {
                    Text(answer.text, style = MaterialTheme.typography.bodyLarge)
                    if (answer.citations.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                            answer.citations.forEach { CitationCell(it, onOpen) }
                        }
                    }
                    Text(
                        stringResource(R.string.search_ask_caution),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CitationCell(citation: Citation, onOpen: (SearchHit) -> Unit) {
    val hit = citation.hit
    val at = hit.timestampMs
    val context = LocalContext.current
    val model: Any = if (at != null) ImageRequest.Builder(context).data(hit.item.contentUri).videoFrameMillis(at).build() else GridThumbnail(hit.item.contentUri)
    val basis = stringResource(citation.basis.label())
    val number = stringResource(R.string.search_source_number, citation.number)
    Column(Modifier.width(CELL), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
        MediaThumbnail(
            model = model,
            contentDescription = listOfNotNull(stringResource(R.string.search_source_spoken, citation.number), stringResource(hit.item.kindLabel()), at?.let { stringResource(R.string.search_at, formatDuration(it)) }, basis).joinToString(", "),
            selected = false,
            label = at?.let { formatDuration(it) },
            modifier = Modifier.size(CELL).clickable { onOpen(hit) },
        )
        // A guess reads apart from what was seen or written (RAG-003): its own color and slanted.
        val guessed = citation.basis == Basis.INFERRED
        Text(
            "$number $basis",
            style = MaterialTheme.typography.labelSmall,
            fontStyle = if (guessed) FontStyle.Italic else FontStyle.Normal,
            color = if (guessed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

private val CELL = 72.dp

private fun Basis.label() = when (this) {
    Basis.OBSERVED -> R.string.search_basis_observed
    Basis.USER_STATED -> R.string.search_basis_user_stated
    Basis.INFERRED -> R.string.search_basis_inferred
}

@IrohacoPreviews
@Composable
private fun AnswerCardPreview() {
    val photo = SearchHit(TimelineItem("p", MediaKind.IMAGE, "content://p", 0), null, setOf(Evidence.MEANING), null)
    val memo = SearchHit(TimelineItem("m", MediaKind.IMAGE, "content://m", 0), null, setOf(Evidence.MEMO), "Grandma's birthday")
    val scene = SearchHit(TimelineItem("v", MediaKind.VIDEO, "content://v", 0), 72_000, setOf(Evidence.MEANING), null)
    IrohacoTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.l)) {
                AnswerCard(
                    asking = false,
                    answer = AskResult.Answered(
                        "It was Grandma's 80th birthday [2], at the harbour [1]. The cake was cut in the evening [3].",
                        known = true,
                        citations = listOf(Citation(photo, Basis.OBSERVED, 1), Citation(memo, Basis.USER_STATED, 2), Citation(scene, Basis.INFERRED, 3)),
                    ),
                    onAsk = {}, onOpen = {}, onOpenSettings = {},
                )
                AnswerCard(asking = false, answer = AskResult.NoModel, onAsk = {}, onOpen = {}, onOpenSettings = {})
                AnswerCard(asking = false, answer = null, onAsk = {}, onOpen = {}, onOpenSettings = {})
            }
        }
    }
}
