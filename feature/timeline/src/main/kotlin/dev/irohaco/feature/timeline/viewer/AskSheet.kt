package dev.irohaco.feature.timeline.viewer

import dev.irohaco.core.application.THINK_ON
import dev.irohaco.core.application.THINK_OFF
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import dev.irohaco.core.application.ChatWithLibrary
import dev.irohaco.core.application.Citation
import dev.irohaco.core.application.QueryReader
import dev.irohaco.core.application.ReadQuery
import java.time.ZonedDateTime
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
 * answered with [found], what the search found, instead. [answering] is false while it is not yet known
 * which, so the wait says the photos are being looked for rather than an answer written.
 */
data class AskTurn(
    val question: String,
    val answer: AskResult? = null,
    val partial: String = "",
    val found: ChatWithLibrary.Found? = null,
    val answering: Boolean = true,
    /** What the model thought so far, before [partial]. */
    val thought: String = "",
    /** The search the question was read as, for showing its photos; null before it is read. */
    val read: ReadQuery? = null,
)

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
    onEffort: (String?) -> Unit = {},
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
            onEffort = onEffort,
        )
    }
}

/**
 * The turns as a chat, the user's questions on the right and the AI's replies on the left, with the field for
 * the next question kept under them; also the "Ask AI" screen's body (#202).
 */
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
    /** Sets how deeply the next questions are thought about; null for the model's own depth. */
    onEffort: (String?) -> Unit = {},
    /** Shows the photos searched with these conditions as a grid that can be narrowed further; null hides the button. */
    onShowPhotos: ((ReadQuery) -> Unit)? = null,
    /** Questions offered before the first one, sent as they are when tapped. */
    examples: List<String> = emptyList(),
) {
    var question by rememberSaveable { mutableStateOf("") }
    val busy = turns.lastOrNull()?.let { it.answer == null && it.found == null } == true
    val send = {
        if (question.isNotBlank() && !busy) {
            onAsk(question)
            question = ""
        }
    }
    val scroll = rememberScrollState()
    // The newest message stays in view as it is asked and written, as in a chat.
    LaunchedEffect(turns.size, turns.lastOrNull()?.partial?.length, turns.lastOrNull()?.thought?.length, turns.lastOrNull()?.answer, turns.lastOrNull()?.found) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    // Messages sit on the field when there are few, as in a chat; the sheet wraps them instead.
    Column(modifier.fillMaxWidth().navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.Bottom) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
        ) {
            if (turns.isEmpty() && examples.isNotEmpty()) Examples(examples) { onAsk(it) }
            turns.forEach { turn ->
                QuestionBubble(turn.question, Modifier.align(Alignment.End))
                Reply {
                    val found = turn.found
                    if (found != null) {
                        FoundCard(found, onOpen, onShowAll = onShowPhotos?.let { show -> { show(found.read) } })
                        return@Reply
                    }
                    AnswerCard(
                        asking = turn.answer == null,
                        answer = turn.answer,
                        onAsk = { onAsk(turn.question) },
                        onOpen = onOpen,
                        onOpenSettings = onOpenSettings,
                        partial = turn.partial,
                        thought = turn.thought,
                        askingLabel = if (turn.answering) askingLabel else R.string.ask_finding,
                        caution = caution,
                    )
                    if (onShowPhotos != null && turn.answer != null) {
                        TextButton(onClick = { onShowPhotos(turn.read ?: QueryReader.read(turn.question, ZonedDateTime.now())) }) {
                            Icon(painterResource(IrohacoIcons.Photo), contentDescription = null)
                            Text(stringResource(R.string.ask_show_photos), modifier = Modifier.padding(start = IrohacoSpacing.s))
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
            if (models != null && models.all.isNotEmpty()) ModelPicker(models, onChoose, onEffort)
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text(hint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4,
                shape = RoundedCornerShape(28.dp),
                trailingIcon = {
                    IconButton(onClick = send, enabled = question.isNotBlank() && !busy) {
                        Icon(painterResource(IrohacoIcons.Send), stringResource(R.string.viewer_ask_send))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The user's question, on the right in its own color. */
@Composable
private fun QuestionBubble(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp),
        modifier = modifier.padding(start = 48.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.m))
    }
}

/** The AI's reply, on the left beside its mark. */
@Composable
private fun Reply(content: @Composable ColumnScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = CircleShape, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(IrohacoIcons.SmartToy), contentDescription = null, modifier = Modifier.size(18.dp)) }
        }
        Column(Modifier.weight(1f), content = content)
    }
}

/** What can be asked, before the first question. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Examples(examples: List<String>, onAsk: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
        Text(stringResource(R.string.ask_intro), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
            examples.forEach { example -> SuggestionChip(onClick = { onAsk(example) }, label = { Text(example) }) }
        }
    }
}

/**
 * Which model answers and how deeply it thinks, in one menu as chat apps have it: the models, then the depths the chosen
 * one takes. A depth is the service's own effort value as its API names it, or, on the device, with or without
 * thoughts; "Default" sends nothing, so the model's own or the saved setting applies.
 */
@Composable
private fun ModelPicker(models: AnswerChoices, onChoose: (String) -> Unit, onEffort: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = models.all.firstOrNull { it.id == models.current }
    val effort = models.effort?.let { effortLabel(it) }
    Box {
        // A plain button with the model's name and depth, as chat apps show the model in use.
        TextButton(onClick = { open = true }) {
            current?.let { ModelMark(it.name, it.id) }
            Text(
                current?.name ?: stringResource(R.string.ask_model_choose),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = IrohacoSpacing.s),
            )
            effort?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = IrohacoSpacing.xs)) }
            Icon(painterResource(IrohacoIcons.ExpandMore), contentDescription = stringResource(R.string.ask_model_choose))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading(R.string.ask_model_section)
            models.all.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.name)
                            Text(
                                choice.service?.let { stringResource(R.string.ask_model_at, it) } ?: stringResource(R.string.ask_model_on_device),
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
            current?.efforts?.takeIf { it.isNotEmpty() }?.let { efforts ->
                HorizontalDivider()
                MenuHeading(if (THINK_OFF in efforts) R.string.ask_thinking_section else R.string.ask_effort_section)
                (listOf<String?>(null) + efforts).forEach { value ->
                    DropdownMenuItem(
                        text = { Text(effortLabel(value)) },
                        trailingIcon = if (value == models.effort) {
                            { Icon(painterResource(IrohacoIcons.Check), contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            open = false
                            onEffort(value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuHeading(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
    )
}

@Composable
private fun effortLabel(value: String?): String = when (value) {
    null -> stringResource(R.string.ask_effort_default)
    THINK_OFF -> stringResource(R.string.ask_effort_off)
    THINK_ON -> stringResource(R.string.ask_effort_on)
    else -> value
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
                    AskTurn("Where is it?", thought = "A pier and boats, so a harbour. The sign is too small to read."),
                    AskTurn("Fireworks last summer", found = ChatWithLibrary.Found(ReadQuery("fireworks", 1_750_000_000_000, 1_756_000_000_000, MediaKind.IMAGE), (1..4).map { photo.copy(item = photo.item.copy(assetId = "p$it")) })),
                ),
                hint = "Ask about this photo",
                onAsk = {},
                onOpen = {},
                onOpenSettings = {},
                askingLabel = R.string.viewer_asking,
                caution = R.string.viewer_ask_caution,
                models = AnswerChoices(listOf(AnswerChoice("gemma", "Gemma 4 E2B"), AnswerChoice("claude", "Claude", "Anthropic", listOf("low", "high"))), "claude", "high"),
            )
        }
    }
}

@IrohacoPreviews
@Composable
private fun AskContentEmptyPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            AskContent(
                turns = emptyList(),
                hint = "Ask about your photos",
                onAsk = {},
                onOpen = {},
                onOpenSettings = {},
                askingLabel = R.string.search_asking,
                caution = R.string.search_ask_caution,
                examples = listOf("Fireworks last summer", "Where did I go last summer?"),
            )
        }
    }
}
