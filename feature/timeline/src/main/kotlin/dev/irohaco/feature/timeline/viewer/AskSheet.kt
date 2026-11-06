package dev.irohaco.feature.timeline.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import dev.irohaco.core.application.AnswerChoice
import dev.irohaco.core.application.AnswerChoices
import dev.irohaco.core.application.AskResult
import dev.irohaco.core.application.Basis
import dev.irohaco.core.application.Citation
import dev.irohaco.core.application.SearchHit
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.component.IrohacoBottomSheet
import dev.irohaco.core.designsystem.component.ModelMark
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.search.AnswerCard
import dev.irohaco.feature.timeline.search.FoundCard

/**
 * One question and its answer; [answer] is null while the model writes [partial]. A request for photos is
 * answered with [found], what the search found, instead.
 */
data class AskTurn(val question: String, val answer: AskResult? = null, val partial: String = "", val found: List<SearchHit>? = null)

/** Questions about the photo or scene on screen and the answers, one under another (#201). */
@Composable
internal fun AskSheet(
    turns: List<AskTurn>,
    isVideo: Boolean,
    models: AnswerChoices?,
    onChoose: (String) -> Unit,
    onAsk: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    IrohacoBottomSheet(onDismiss = onDismiss) {
        AskContent(
            turns = turns,
            hint = stringResource(if (isVideo) R.string.viewer_ask_hint_video else R.string.viewer_ask_hint),
            onAsk = onAsk,
            onOpen = { onDismiss() }, // the photo is open already
            onOpenSettings = onOpenSettings,
            askingLabel = R.string.viewer_asking,
            caution = R.string.viewer_ask_caution,
            models = models,
            onChoose = onChoose,
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
    models: AnswerChoices? = null,
    onChoose: (String) -> Unit = {},
    /** Shows the photos a question is about as a grid that can be narrowed further; null hides the button. */
    onShowPhotos: ((String) -> Unit)? = null,
) {
    var question by rememberSaveable { mutableStateOf("") }
    val busy = turns.lastOrNull()?.let { it.answer == null && it.found == null } == true
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
            turn.found?.let { found ->
                FoundCard(found, onOpen, onShowAll = onShowPhotos?.let { show -> { show(turn.question) } })
                return@forEach
            }
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
            if (onShowPhotos != null && turn.answer != null) {
                TextButton(onClick = { onShowPhotos(turn.question) }) {
                    Icon(painterResource(IrohacoIcons.Photo), contentDescription = null)
                    Text(stringResource(R.string.ask_show_photos), modifier = Modifier.padding(start = IrohacoSpacing.s))
                }
            }
        }
        if (models != null && models.all.isNotEmpty()) ModelPicker(models, onChoose)
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

/** Which model answers, switched here instead of in the settings. */
@Composable
private fun ModelPicker(models: AnswerChoices, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = models.all.firstOrNull { it.id == models.current }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(current?.name ?: stringResource(R.string.ask_model_choose)) },
            leadingIcon = current?.let { { ModelMark(it.name, it.id) } },
            trailingIcon = { Icon(painterResource(IrohacoIcons.ExpandMore), contentDescription = stringResource(R.string.ask_model_choose)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.all.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.name)
                            Text(
                                choice.host?.let { stringResource(R.string.ask_model_at, it) } ?: stringResource(R.string.ask_model_on_device),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { ModelMark(choice.name, choice.id) },
                    trailingIcon = if (choice.id == models.current) {
                        { Icon(painterResource(IrohacoIcons.Check), contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        if (choice.id != models.current) onChoose(choice.id)
                    },
                )
            }
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
                    AskTurn("Fireworks last summer", found = listOf(photo, photo.copy(item = photo.item.copy(assetId = "q")))),
                ),
                hint = "Ask about this photo",
                onAsk = {},
                onOpen = {},
                onOpenSettings = {},
                askingLabel = R.string.viewer_asking,
                caution = R.string.viewer_ask_caution,
                models = AnswerChoices(listOf(AnswerChoice("gemma", "Gemma 4 E2B"), AnswerChoice("claude", "Claude", "api.anthropic.com")), "gemma"),
            )
        }
    }
}
