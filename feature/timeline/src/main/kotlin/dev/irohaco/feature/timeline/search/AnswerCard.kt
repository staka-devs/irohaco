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
import dev.irohaco.core.application.ChatWithLibrary
import dev.irohaco.core.application.Citation
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.AssistChip
import dev.irohaco.core.application.Evidence
import dev.irohaco.core.application.SearchHit
import dev.irohaco.core.contracts.EgressDeniedException
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.component.GridThumbnail
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.component.label
import dev.irohaco.core.designsystem.component.remoteFailureText
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
internal fun AnswerCard(
    asking: Boolean,
    answer: AskResult?,
    onAsk: () -> Unit,
    onOpen: (SearchHit) -> Unit,
    onOpenSettings: () -> Unit,
    /** The answer so far while the model writes it. */
    partial: String = "",
    /** What the card says while the model has not written yet, and the caution under an answer. */
    askingLabel: Int = R.string.search_asking,
    caution: Int = R.string.search_ask_caution,
) {
    if (answer == null && !asking) {
        OutlinedButton(onClick = onAsk) { Text(stringResource(R.string.search_ask)) }
        return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
            when (answer) {
                null -> {
                    if (partial.isEmpty()) {
                        Text(stringResource(askingLabel), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(partial, style = MaterialTheme.typography.bodyLarge)
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                AskResult.NoModel -> {
                    Text(stringResource(R.string.search_ask_no_model), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.search_open_settings)) }
                }
                AskResult.NothingFound -> Text(stringResource(R.string.search_ask_nothing), style = MaterialTheme.typography.bodyMedium)
                is AskResult.Failed -> {
                    // A remote model says why; a refusal is not retried until the user changes the grants (AC-009).
                    val reason = remoteFailureText(answer.cause)
                    Text(reason ?: stringResource(R.string.search_ask_failed), style = MaterialTheme.typography.bodyMedium)
                    if (answer.cause is EgressDeniedException) {
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.search_open_settings)) }
                    } else {
                        TextButton(onClick = onAsk) { Text(stringResource(R.string.search_ask_retry)) }
                    }
                }
                is AskResult.NeedsConsent -> {
                    val what = answer.payloads.map { stringResource(it.label()) }.joinToString(" / ")
                    Text(stringResource(R.string.search_ask_consent, answer.model, answer.host, what), style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = {
                            answer.agree()
                            onAsk()
                        },
                    ) { Text(stringResource(R.string.search_ask_consent_send)) }
                }
                is AskResult.Answered -> {
                    Text(answer.text, style = MaterialTheme.typography.bodyLarge)
                    if (answer.citations.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                            answer.citations.forEach { CitationCell(it, onOpen) }
                        }
                    }
                    Text(
                        stringResource(caution, answer.remoteModel ?: stringResource(R.string.ask_on_device_ai)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A request for photos answered with what the search found: the conditions it was read as, then the first
 * photos in a grid. [onShowAll] opens them all on the search page, where the conditions can be changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FoundCard(found: ChatWithLibrary.Found, onOpen: (SearchHit) -> Unit, onShowAll: (() -> Unit)?) {
    val read = found.read
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
            Text(stringResource(if (found.hits.isEmpty()) R.string.ask_found_nothing else R.string.ask_found), style = MaterialTheme.typography.bodyLarge)
            // How the request was read, so a wrong reading shows at once; tapping one changes it on the search page.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                val conditions = listOfNotNull(
                    read.rest.takeIf { read.hasWords }?.let { stringResource(R.string.ask_found_words, it) },
                    read.fromMs?.let { from -> read.untilMs?.let { until -> dateRangeLabel(from, until) } },
                    read.kind?.let { stringResource(if (it == MediaKind.VIDEO) R.string.search_videos else R.string.search_photos) },
                )
                conditions.forEach { AssistChip(onClick = { onShowAll?.invoke() }, enabled = onShowAll != null, label = { Text(it) }) }
            }
            found.hits.take(FOUND_SHOWN).chunked(FOUND_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
                    row.forEach { hit ->
                        MediaThumbnail(
                            model = hitModel(hit),
                            contentDescription = listOfNotNull(stringResource(hit.item.kindLabel()), hit.timestampMs?.let { stringResource(R.string.search_at, formatDuration(it)) }).joinToString(", "),
                            selected = false,
                            label = hit.timestampMs?.let { formatDuration(it) },
                            modifier = Modifier.weight(1f).aspectRatio(1f).clickable { onOpen(hit) },
                        )
                    }
                    // A short last row keeps the cells the same size as the full rows above it.
                    repeat(FOUND_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (onShowAll != null && found.hits.isNotEmpty()) {
                TextButton(onClick = onShowAll) { Text(stringResource(R.string.ask_found_all)) }
            }
        }
    }
}

private const val FOUND_COLUMNS = 3
private const val FOUND_SHOWN = 6

@Composable
private fun hitModel(hit: SearchHit): Any {
    val at = hit.timestampMs ?: return GridThumbnail(hit.item.contentUri)
    return ImageRequest.Builder(LocalContext.current).data(hit.item.contentUri).videoFrameMillis(at).build()
}

@Composable
private fun CitationCell(citation: Citation, onOpen: (SearchHit) -> Unit) {
    val hit = citation.hit
    val at = hit.timestampMs
    val model = hitModel(hit)
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
                AnswerCard(asking = true, answer = null, onAsk = {}, onOpen = {}, onOpenSettings = {}, partial = "It was Grandma's 80th")
                AnswerCard(asking = false, answer = AskResult.NoModel, onAsk = {}, onOpen = {}, onOpenSettings = {})
                AnswerCard(
                    asking = false,
                    answer = AskResult.NeedsConsent("Claude", "api.anthropic.com", setOf(EgressPayload.QUERY_TEXT, EgressPayload.IMAGE_PIXELS)) {},
                    onAsk = {},
                    onOpen = {},
                    onOpenSettings = {},
                )
                AnswerCard(asking = false, answer = null, onAsk = {}, onOpen = {}, onOpenSettings = {})
            }
        }
    }
}
