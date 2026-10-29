package dev.irohaco.feature.timeline.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import dev.irohaco.core.application.AskResult
import dev.irohaco.core.application.Basis
import dev.irohaco.core.application.Citation
import dev.irohaco.core.application.SearchHit
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.component.IrohacoBottomSheet
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.search.AnswerCard

/** One question and its answer; [answer] is null while the model writes [partial]. */
data class AskTurn(val question: String, val answer: AskResult? = null, val partial: String = "")

/** Questions about the photo or scene on screen and the answers, one under another (#201). */
@Composable
internal fun AskSheet(turns: List<AskTurn>, isVideo: Boolean, onAsk: (String) -> Unit, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    IrohacoBottomSheet(onDismiss = onDismiss) {
        AskContent(
            turns = turns,
            hint = stringResource(if (isVideo) R.string.viewer_ask_hint_video else R.string.viewer_ask_hint),
            onAsk = onAsk,
            onOpen = { onDismiss() }, // the photo is open already
            onOpenSettings = onOpenSettings,
            askingLabel = R.string.viewer_asking,
            caution = R.string.viewer_ask_caution,
        )
    }
}

/** The turns one under another and a field for the next question; also the "Ask AI" screen's body (#202). */
@Composable
internal fun AskContent(
    turns: List<AskTurn>,
    hint: String,
    onAsk: (String) -> Unit,
    onOpen: (SearchHit) -> Unit,
    onOpenSettings: () -> Unit,
    askingLabel: Int,
    caution: Int,
    modifier: Modifier = Modifier,
) {
    var question by rememberSaveable { mutableStateOf("") }
    val busy = turns.lastOrNull()?.answer == null && turns.isNotEmpty()
    val send = {
        if (question.isNotBlank() && !busy) {
            onAsk(question)
            question = ""
        }
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = IrohacoSpacing.l).navigationBarsPadding().imePadding(),
        verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
    ) {
        turns.forEach { turn ->
            Text(turn.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.End))
            AnswerCard(
                asking = turn.answer == null,
                answer = turn.answer,
                onAsk = { onAsk(turn.question) },
                onOpen = onOpen,
                onOpenSettings = onOpenSettings,
                partial = turn.partial,
                askingLabel = askingLabel,
                caution = caution,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text(hint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = send, enabled = question.isNotBlank() && !busy) { Text(stringResource(R.string.viewer_ask_send)) }
        }
    }
}

@IrohacoPreviews
@Composable
private fun AskContentPreview() {
    val photo = SearchHit(TimelineItem("p", MediaKind.IMAGE, "content://p", 0), null, emptySet(), null)
    IrohacoTheme(dynamicColor = false) {
        Surface {
            AskContent(
                turns = listOf(
                    AskTurn("What is this?", AskResult.Answered("A pier at dusk [1].", known = true, citations = listOf(Citation(photo, Basis.OBSERVED, 1)))),
                    AskTurn("Where is it?", partial = "Probably a harbour"),
                ),
                hint = "Ask about this photo",
                onAsk = {},
                onOpen = {},
                onOpenSettings = {},
                askingLabel = R.string.viewer_asking,
                caution = R.string.viewer_ask_caution,
            )
        }
    }
}
