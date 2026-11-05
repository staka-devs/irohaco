package dev.irohaco.feature.timeline.ask

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.AnswerChoices
import dev.irohaco.core.application.AskLibrary
import dev.irohaco.core.application.AskResult
import dev.irohaco.core.application.ChatWithLibrary
import dev.irohaco.core.application.SearchHit
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.viewer.AskContent
import dev.irohaco.feature.timeline.viewer.AskTurn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Free questions about the library, answered from the photos and scenes the AI finds for each (#202). */
class AskViewModel(private val chat: ChatWithLibrary, private val library: AskLibrary? = null) : ViewModel() {
    private val _turns = MutableStateFlow<List<AskTurn>>(emptyList())
    val turns: StateFlow<List<AskTurn>> = _turns.asStateFlow()
    private var asking: Job? = null

    /** Loads the model while the user types the first question. */
    fun warmUp() = chat.warmUp()

    /** The models that can answer, switched above the question field. */
    val models: StateFlow<AnswerChoices?> =
        (library?.choices ?: flowOf(null)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun choose(id: String) {
        val library = library ?: return
        viewModelScope.launch {
            library.choose(id)
            library.warmUp()
        }
    }

    fun ask(question: String) {
        if (question.isBlank()) return
        // A failed turn is asked again rather than kept.
        val kept = _turns.value.filterNot { it.answer is AskResult.Failed || it.answer is AskResult.NeedsConsent }
        val earlier = kept.mapNotNull { turn -> (turn.answer as? AskResult.Answered)?.let { ChatWithLibrary.Turn(turn.question, it) } }
        _turns.value = kept + AskTurn(question.trim())
        asking?.cancel()
        asking = viewModelScope.launch {
            val answer = chat.answer(question, earlier) { text -> _turns.update { it.dropLast(1) + it.last().copy(partial = text) } }
            _turns.update { it.dropLast(1) + it.last().copy(answer = answer, partial = "") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(viewModel: AskViewModel, onOpen: (SearchHit) -> Unit, onOpenSettings: () -> Unit, onBack: () -> Unit, onShowPhotos: ((String) -> Unit)? = null) {
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.warmUp() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ask_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) } },
            )
        },
    ) { padding ->
        AskContent(
            turns = turns,
            hint = stringResource(R.string.ask_hint),
            onAsk = viewModel::ask,
            onOpen = onOpen,
            onOpenSettings = onOpenSettings,
            askingLabel = R.string.search_asking,
            caution = R.string.search_ask_caution,
            modifier = Modifier.padding(padding),
            models = models,
            onChoose = viewModel::choose,
            onShowPhotos = onShowPhotos,
        )
    }
}
