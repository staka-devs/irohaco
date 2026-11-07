package dev.irohaco.feature.timeline.search

import dev.irohaco.feature.timeline.kindLabel
import dev.irohaco.feature.timeline.ChoiceChip
import dev.irohaco.core.designsystem.component.IrohacoBottomSheet
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.material3.ListItem
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import dev.irohaco.core.application.AskLibrary
import dev.irohaco.core.application.AskResult
import dev.irohaco.core.application.Evidence
import dev.irohaco.core.application.ManageCollections
import dev.irohaco.core.application.SearchFilters
import dev.irohaco.core.application.SearchHit
import dev.irohaco.core.application.SearchMedia
import dev.irohaco.core.application.SearchResults
import dev.irohaco.core.application.SpaceChoice
import dev.irohaco.core.application.QueryReader
import dev.irohaco.core.application.ReadQuery
import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.Tag
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.designsystem.component.BadgeStatus
import dev.irohaco.core.designsystem.component.GridThumbnail
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.component.StatusBadge
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.TagFilterChips
import dev.irohaco.feature.timeline.badgeIcon
import dev.irohaco.feature.timeline.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The search's conditions and what they found; [results] is null before the first search. */
data class SearchUi(
    val query: String = "",
    /** The query without the dates and the kind read out of it (#203); what is searched. */
    val rest: String = "",
    /** Only files at the place the query names (#203). */
    val atPlace: Boolean = true,
    val byMeaning: Boolean = true,
    val byText: Boolean = true,
    val byMemo: Boolean = true,
    val kind: MediaKind? = null,
    /** A space picked for this search without making it the default, such as one still being built (AC-005). */
    val spaceId: String? = null,
    /** Searches every model and shows each one's rank (SRCH-005). */
    val compare: Boolean = false,
    /** Capture time in [from, until) and tags (SRCH-004). */
    val filters: SearchFilters = SearchFilters(),
    /** Places guessed from photos taken near in time count too (`include_inferred`, SRCH-003). */
    val inferredPlaces: Boolean = true,
    val searching: Boolean = false,
    val results: SearchResults? = null,
    /** An answer from the results is being made (RAG-001); [answer] is what came of it, null before asking. */
    val asking: Boolean = false,
    val answer: AskResult? = null,
    /** The answer so far while the model writes it. */
    val partial: String = "",
    /** What the model thought so far, before the answer. */
    val thought: String = "",
)

class SearchViewModel(
    private val search: SearchMedia,
    collections: ManageCollections? = null,
    /** Opened from a photo's "find similar": the results are the photos like it, and there is no query. */
    private val similarTo: String? = null,
    /** Answers the query from the results with a local model (RAG-001); null hides the button. */
    private val askLibrary: AskLibrary? = null,
    /** Opened from a chat: searched at once with the conditions the chat read, which can then be changed. */
    initial: ReadQuery? = null,
) : ViewModel() {
    val canAsk = askLibrary != null && similarTo == null

    val spaces: StateFlow<List<SpaceChoice>> = search.spaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The tags to narrow by (SRCH-004). */
    val tags: StateFlow<List<Tag>> = (collections?.tags() ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()

    private var running: Job? = null
    private var asking: Job? = null

    /** Answers the query from the results shown now; a new search drops the answer. */
    fun ask() {
        val ui = _ui.value
        val hits = ui.results?.hits ?: return
        val library = askLibrary ?: return
        asking?.cancel()
        _ui.update { it.copy(asking = true, answer = null, partial = "", thought = "") }
        asking = viewModelScope.launch {
            val answer = library.ask(ui.query, hits) { so -> _ui.update { it.copy(partial = so.text, thought = so.thought) } }
            _ui.update { it.copy(asking = false, answer = answer, partial = "", thought = "") }
        }
    }

    init {
        if (similarTo != null) change { it }
        initial?.let { read ->
            change {
                it.copy(
                    query = read.rest,
                    rest = read.rest,
                    kind = read.kind,
                    filters = it.filters.copy(fromMs = read.fromMs, untilMs = read.untilMs),
                    atPlace = true,
                )
            }
        }
    }

    /** Capture times from [fromMs] until [untilMs] (exclusive); nulls clear the range. */
    fun showDates(fromMs: Long?, untilMs: Long?) = change { it.copy(filters = it.filters.copy(fromMs = fromMs, untilMs = untilMs)) }

    fun showTags(tagIds: Set<String>) = change { it.copy(filters = it.filters.copy(tagIds = tagIds)) }

    /** The dates and the kind the query names become the conditions, which the user can still change (#203). */
    fun search(query: String) {
        val read = QueryReader.read(query, ZonedDateTime.now())
        change {
            it.copy(
                query = query.trim(),
                rest = read.rest,
                kind = read.kind ?: it.kind,
                filters = if (read.fromMs != null) it.filters.copy(fromMs = read.fromMs, untilMs = read.untilMs) else it.filters,
                atPlace = true,
            )
        }
    }

    fun toggleAtPlace() = change { it.copy(atPlace = !it.atPlace) }

    fun toggleMeaning() = change { it.copy(byMeaning = !it.byMeaning) }

    fun toggleText() = change { it.copy(byText = !it.byText) }

    fun toggleMemo() = change { it.copy(byMemo = !it.byMemo) }

    fun toggleCompare() = change { it.copy(compare = !it.compare) }

    fun toggleInferredPlaces() = change { it.copy(inferredPlaces = !it.inferredPlaces) }

    fun showKind(kind: MediaKind?) = change { it.copy(kind = kind) }

    /** A READY space becomes the default for every search (AC-004); another one is searched just this time. */
    fun pickSpace(space: EmbeddingSpace) {
        if (space.state == SpaceState.READY) {
            viewModelScope.launch {
                search.setDefault(space.spaceId)
                change { it.copy(spaceId = null) }
            }
        } else {
            change { it.copy(spaceId = space.spaceId) }
        }
    }

    private fun change(edit: (SearchUi) -> SearchUi) {
        asking?.cancel()
        _ui.update { edit(it).copy(asking = false, answer = null, partial = "", thought = "") }
        val ui = _ui.value
        running?.cancel()
        if (ui.query.isEmpty() && similarTo == null) {
            _ui.update { it.copy(searching = false, results = null) }
            return
        }
        _ui.update { it.copy(searching = true) }
        running = viewModelScope.launch {
            val results = if (similarTo != null) {
                search.similar(similarTo, ui.spaceId, ui.kind, ui.filters)
            } else {
                search(ui.rest.ifEmpty { ui.query }, ui.spaceId, ui.byMeaning, ui.byText, ui.kind, ui.compare, ui.byMemo, ui.filters, ui.inferredPlaces, ui.atPlace)
            }
            _ui.update { it.copy(searching = false, results = results) }
            // Loading a model takes half a minute; start while the user looks at the results.
            if (canAsk && results.hits.isNotEmpty()) askLibrary?.warmUp()
        }
    }
}

/** Opens a photo, or a video at the matched scene (PlayVideoAt, AC-002). */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenMedia: (String) -> Unit,
    onPlayAt: (String, Long) -> Unit,
    onOpenSettings: () -> Unit,
    /** Set for the "similar photos" page, which has a way back instead of a search bar. */
    onBack: (() -> Unit)? = null,
    /** Opens the screen to ask AI freely (#202); null hides its button. */
    onOpenAsk: (() -> Unit)? = null,
    /** Set when opened from a chat's question: the bar keeps its search and gets a way back to the chat. */
    onBackToChat: (() -> Unit)? = null,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    SearchContent(
        ui = ui,
        spaces = spaces,
        tags = tags,
        onDates = viewModel::showDates,
        onTags = viewModel::showTags,
        onToggleInferredPlaces = viewModel::toggleInferredPlaces,
        onToggleAtPlace = viewModel::toggleAtPlace,
        onBack = onBack,
        onBackToChat = onBackToChat,
        onSearch = viewModel::search,
        onToggleMeaning = viewModel::toggleMeaning,
        onToggleText = viewModel::toggleText,
        onToggleMemo = viewModel::toggleMemo,
        onKind = viewModel::showKind,
        onToggleCompare = viewModel::toggleCompare,
        onSpace = viewModel::pickSpace,
        onOpen = { hit -> hit.timestampMs?.let { onPlayAt(hit.item.assetId, it) } ?: onOpenMedia(hit.item.assetId) },
        onOpenSettings = onOpenSettings,
        onAsk = if (viewModel.canAsk) viewModel::ask else null,
        onOpenAsk = onOpenAsk,
    )
}

/**
 * Search (DESIGN §4.3, SRCH-001〜006): a search bar, condition chips, the model switch or a comparison
 * of every model, a banner when an index does not cover everything, and photos and video scenes in one grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchContent(
    ui: SearchUi,
    spaces: List<SpaceChoice>,
    onSearch: (String) -> Unit,
    onToggleMeaning: () -> Unit,
    onToggleText: () -> Unit,
    onToggleMemo: () -> Unit,
    onKind: (MediaKind?) -> Unit,
    onToggleCompare: () -> Unit,
    onSpace: (EmbeddingSpace) -> Unit,
    onOpen: (SearchHit) -> Unit,
    onOpenSettings: () -> Unit,
    tags: List<Tag> = emptyList(),
    onDates: (Long?, Long?) -> Unit = { _, _ -> },
    onTags: (Set<String>) -> Unit = {},
    onToggleInferredPlaces: () -> Unit = {},
    onToggleAtPlace: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onAsk: (() -> Unit)? = null,
    onOpenAsk: (() -> Unit)? = null,
    onBackToChat: (() -> Unit)? = null,
) {
    val text = rememberTextFieldState(ui.query)
    var tuning by rememberSaveable { mutableStateOf(false) }
    // The query arrives after the first frame when the page opens with one, so the field takes it up then.
    LaunchedEffect(ui.query) { if (text.text.isEmpty() && ui.query.isNotEmpty()) text.setTextAndPlaceCursorAtEnd(ui.query) }
    val labels = spaceLabels(spaces)
    Scaffold { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(start = IrohacoSpacing.l, end = IrohacoSpacing.l, top = padding.calculateTopPadding(), bottom = IrohacoSpacing.l),
            horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (onBack != null) {
                    SimilarHeader(onBack)
                    return@item
                }
                // A field shaped like a search bar, not a SearchBar: the results show below it on this screen, and a
                // collapsed SearchBar keeps the keyboard away until it expands to a full-screen bar of its own.
                TextField(
                    state = text,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    leadingIcon = {
                        if (onBackToChat != null) {
                            IconButton(onClick = onBackToChat) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.search_back_to_chat)) }
                        } else {
                            Icon(painterResource(IrohacoIcons.Search), null)
                        }
                    },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { tuning = true }) { Icon(painterResource(IrohacoIcons.Tune), stringResource(R.string.search_options)) }
                            IconButton(onClick = onOpenSettings) { Icon(painterResource(IrohacoIcons.Settings), stringResource(R.string.timeline_settings)) }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    onKeyboardAction = { onSearch(text.text.toString()) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    shape = SearchBarDefaults.inputFieldShape,
                    colors = SearchBarDefaults.inputFieldColors().copy(
                        focusedContainerColor = SearchBarDefaults.colors().containerColor,
                        unfocusedContainerColor = SearchBarDefaults.colors().containerColor,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
            }
            // Asking AI is a way of its own beside the search, not hidden in the bar.
            if (onOpenAsk != null && ui.results == null) item(span = { GridItemSpan(maxLineSpan) }) { AskEntry(onOpenAsk) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Conditions(
                    ui, spaces, labels, onToggleMeaning, onToggleText, onToggleMemo, onKind, onToggleCompare, onSpace, tags, onDates, onTags,
                    onToggleInferredPlaces, onToggleAtPlace, similar = onBack != null, tuning = tuning, onTuned = { tuning = false },
                )
            }
            ui.results?.let { results ->
                item(span = { GridItemSpan(maxLineSpan) }) { Notices(ui, results, labels, onOpenSettings) }
            }
            if (ui.searching) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (onAsk != null && !ui.searching && ui.results?.hits.orEmpty().isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { AnswerCard(ui.asking, ui.answer, onAsk, onOpen, onOpenSettings, ui.partial, thought = ui.thought) }
            }
            val hits = ui.results?.hits.orEmpty()
            val compared = ui.results?.searched.orEmpty().takeIf { it.size > 1 }?.map { it.space.spaceId }
            items(hits, key = { "${it.item.assetId}@${it.timestampMs}" }) { hit -> HitCell(hit, compared, labels, onOpen) }
            if (ui.results != null && hits.isEmpty() && !ui.searching) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.search_nothing), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = IrohacoSpacing.xl))
                }
            }
            if (ui.results == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Conditions(
    ui: SearchUi,
    spaces: List<SpaceChoice>,
    labels: Map<String, String>,
    onToggleMeaning: () -> Unit,
    onToggleText: () -> Unit,
    onToggleMemo: () -> Unit,
    onKind: (MediaKind?) -> Unit,
    onToggleCompare: () -> Unit,
    onSpace: (EmbeddingSpace) -> Unit,
    tags: List<Tag>,
    onDates: (Long?, Long?) -> Unit,
    onTags: (Set<String>) -> Unit,
    onToggleInferredPlaces: () -> Unit,
    onToggleAtPlace: () -> Unit,
    /** The similar photos page: no query, so only conditions on the files apply. */
    similar: Boolean,
    /** The search options sheet is open: what to search in, which rarely changes, kept off the screen. */
    tuning: Boolean = false,
    onTuned: () -> Unit = {},
) {
    val searchable = spaces.count { it.space.state in PICKABLE }
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    if (pickingDates) {
        DateRangeDialog(ui.filters.fromMs, ui.filters.untilMs, onDismiss = { pickingDates = false }, onClear = { pickingDates = false; onDates(null, null) }) { from, until ->
            pickingDates = false
            onDates(from, until)
        }
    }
    if (tuning && !similar) {
        IrohacoBottomSheet(onDismiss = onTuned) {
            SearchOptions(ui, searchable > 1, onToggleMeaning, onToggleText, onToggleMemo, onToggleInferredPlaces, onToggleCompare)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
        // The conditions on the files, in one row that scrolls sideways.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
            ChoiceChip(ui.kind, listOf(null, MediaKind.IMAGE, MediaKind.VIDEO), { kindLabel(it) }, onKind)
            val dates = ui.filters.fromMs?.let { from -> ui.filters.untilMs?.let { until -> dateRangeLabel(from, until) } }
            FilterChip(
                dates != null,
                { pickingDates = true },
                label = { Text(dates ?: stringResource(R.string.search_dates)) },
                trailingIcon = { Icon(painterResource(IrohacoIcons.ExpandMore), null) },
            )
            // The place the query names: on, only files taken there show (#203).
            ui.results?.place?.let { place ->
                FilterChip(
                    ui.atPlace,
                    onToggleAtPlace,
                    label = { Text(stringResource(R.string.search_at_place, place)) },
                    leadingIcon = { Icon(painterResource(if (ui.atPlace) IrohacoIcons.Check else IrohacoIcons.LocationOn), null) },
                )
            }
            TagFilterChips(tags, ui.filters.tagIds, onTags)
        }
        // The model switch appears once there is a choice (DESIGN §3: a few models side by side).
        if (spaces.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
                spaces.forEach { (space, coverage) ->
                    val selected = if (ui.compare) space.state in PICKABLE else if (ui.spaceId != null) space.spaceId == ui.spaceId else space.isDefault
                    FilterChip(
                        selected = selected,
                        onClick = { onSpace(space) },
                        enabled = space.state in PICKABLE && !ui.compare,
                        // Comparing, each model gets a letter the result cells use for its rank.
                        label = { Text(listOfNotNull(letter(ui, space.spaceId), labels.getValue(space.spaceId)).joinToString("  ")) },
                        trailingIcon = space.badge(coverage)?.let { { StatusBadge(it) } },
                    )
                }
            }
        }
    }
}

/** What the search looks in, where it takes places from, and the models side by side, each under its heading. */
@Composable
private fun SearchOptions(
    ui: SearchUi,
    /** Comparing searches every model at once, so it needs two of them (SRCH-005). */
    comparable: Boolean,
    onToggleMeaning: () -> Unit,
    onToggleText: () -> Unit,
    onToggleMemo: () -> Unit,
    onToggleInferredPlaces: () -> Unit,
    onToggleCompare: () -> Unit,
) {
    @Composable
    fun Heading(text: Int) = Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = IrohacoSpacing.l, end = IrohacoSpacing.l, top = IrohacoSpacing.m),
    )

    @Composable
    fun Option(label: Int, hint: Int, on: Boolean, enabled: Boolean = true, onToggle: () -> Unit) = ListItem(
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Switch(checked = on, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(on, enabled = enabled, role = Role.Switch) { onToggle() },
    ) { Text(stringResource(label)) }
    Column(Modifier.padding(bottom = IrohacoSpacing.l)) {
        Text(stringResource(R.string.search_options), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = IrohacoSpacing.l))
        Heading(R.string.search_options_sources)
        Option(R.string.search_by_meaning, R.string.search_by_meaning_hint, ui.byMeaning, onToggle = onToggleMeaning)
        Option(R.string.search_by_text, R.string.search_by_text_hint, ui.byText, onToggle = onToggleText)
        Option(R.string.search_by_memo, R.string.search_by_memo_hint, ui.byMemo, onToggle = onToggleMemo)
        Heading(R.string.search_options_places)
        Option(R.string.search_inferred_places, R.string.search_inferred_places_hint, ui.inferredPlaces, onToggle = onToggleInferredPlaces)
        if (comparable) {
            Heading(R.string.search_options_models)
            Option(R.string.search_compare, R.string.search_compare_hint, ui.compare, enabled = ui.byMeaning, onToggle = onToggleCompare)
        }
    }
}

@IrohacoPreviews
@Composable
private fun SearchOptionsPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { SearchOptions(SearchUi(), comparable = true, {}, {}, {}, {}, {}) }
    }
}

/** The similar photos page's header: a way back and what the page shows. */
@Composable
private fun SimilarHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = IrohacoSpacing.s)) {
        IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) }
        Text(stringResource(R.string.search_similar_title), style = MaterialTheme.typography.titleLarge)
    }
}

/** Picks a range of days, given and returned as the start of the first and the end of the last whole local day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangeDialog(
    fromMs: Long?,
    untilMs: Long?,
    onDismiss: () -> Unit,
    /** Takes the dates off; offered only while some are set. */
    onClear: (() -> Unit)? = null,
    onPick: (Long, Long) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    // The picker works in UTC days; the filter in this device's days.
    fun toPicker(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val state = rememberDateRangePickerState(toPicker(fromMs), toPicker(untilMs?.minus(1)))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis ?: return@TextButton
                    val end = state.selectedEndDateMillis ?: start
                    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(day(start).atStartOfDay(zone).toInstant().toEpochMilli(), day(end).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                },
                enabled = state.selectedStartDateMillis != null,
            ) { Text(stringResource(R.string.search_dates_apply)) }
        },
        dismissButton = {
            Row {
                if (onClear != null && fromMs != null) TextButton(onClick = onClear) { Text(stringResource(R.string.timeline_filter_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_cancel)) }
            }
        },
    ) {
        DateRangePicker(state, modifier = Modifier.heightIn(max = 520.dp))
    }
}

@Composable
internal fun dateRangeLabel(fromMs: Long, untilMs: Long): String {
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val zone = ZoneId.systemDefault()
    val from = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
    val to = Instant.ofEpochMilli(untilMs - 1).atZone(zone).toLocalDate()
    return if (from == to) formatter.format(from) else "${formatter.format(from)} – ${formatter.format(to)}"
}

@Composable
private fun Notices(ui: SearchUi, results: SearchResults, labels: Map<String, String>, onOpenSettings: () -> Unit) {
    if (ui.byMeaning && results.meaningUnavailable) {
        Banner(BadgeStatus.AiNotInstalled, stringResource(R.string.search_no_index)) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.search_open_settings)) }
        }
        return
    }
    val partial = results.searched.filter { (it.coverage ?: 1f) < 1f }
    if (partial.isEmpty()) return
    val text = partial.map { (space, coverage) ->
        val percent = ((coverage ?: 0f) * 100).toInt()
        // With one model the note needs no name; comparing, each model has its own share.
        if (results.searched.size == 1) stringResource(R.string.search_coverage, percent)
        else stringResource(R.string.search_coverage_of, labels.getValue(space.spaceId), percent)
    }.joinToString("\n")
    // A few photos not indexed yet, such as the latest ones, are only mentioned; the banner is for a share that changes the results.
    if (partial.any { (it.coverage ?: 0f) < LOW_COVERAGE }) {
        Banner(BadgeStatus.LowCoverage, text)
    } else {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Below this share of the library indexed, the results miss enough to say so with a banner. */
private const val LOW_COVERAGE = 0.9f

@Composable
private fun Banner(status: BadgeStatus, text: String, action: (@Composable () -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(IrohacoSpacing.m), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
            StatusBadge(status)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@Composable
private fun HitCell(hit: SearchHit, compared: List<String>?, labels: Map<String, String>, onOpen: (SearchHit) -> Unit) {
    val item = hit.item
    val at = hit.timestampMs
    val context = LocalContext.current
    // A scene shows its own frame; the grid's small copy would show the video's first one.
    val model: Any = if (at != null) ImageRequest.Builder(context).data(item.contentUri).videoFrameMillis(at).build() else GridThumbnail(item.contentUri)
    // The similarity goes with the label of what matched by meaning: the photo, or else its memo.
    val similarity = if (compared == null) hit.similarities.values.singleOrNull() else null
    val scored = if (Evidence.MEANING in hit.evidence) Evidence.MEANING else Evidence.MEMO
    val labelled = hit.evidence.sortedBy { it.ordinal }.map { it to stringResource(it.label()) }
    val evidence = labelled.joinToString(" · ") { (e, label) -> if (e == scored && similarity != null) "$label ${similarity.twoPlaces()}" else label }
    val spokenSimilarity = similarity?.let { stringResource(R.string.search_similarity, it.twoPlaces()) }
    val spoken = labelled.joinToString(" · ") { (e, label) -> if (e == scored && spokenSimilarity != null) "$label, $spokenSimilarity" else label }
    val time = at?.let { formatDuration(it) }
    // TalkBack hears each model by name; the cell shows only its letter.
    val ranks = compared?.map { spaceId ->
        val rank = hit.ranks[spaceId]
        val label = labels.getValue(spaceId)
        if (rank != null) {
            listOfNotNull(stringResource(R.string.search_rank, label, rank), hit.similarities[spaceId]?.let { stringResource(R.string.search_similarity, it.twoPlaces()) }).joinToString(", ")
        } else {
            stringResource(R.string.search_unranked, label)
        }
    }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
        MediaThumbnail(
            model = model,
            contentDescription = (listOfNotNull(stringResource(item.kindLabel()), time?.let { stringResource(R.string.search_at, it) }, spoken) + ranks).joinToString(", "),
            selected = false,
            label = time ?: item.durationMs?.let(::formatDuration),
            labelIcon = item.badgeIcon,
            modifier = Modifier.clickable { onOpen(hit) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(evidence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Each model's own rank and similarity, side by side but never added: they do not compare across models (SRCH-005).
        if (compared != null) {
            Text(
                compared.mapIndexed { i, spaceId ->
                    val rank = hit.ranks[spaceId]
                    if (rank != null) {
                        stringResource(R.string.search_rank_short, LETTERS[i], rank) + hit.similarities[spaceId]?.let { " ${it.twoPlaces()}" }.orEmpty()
                    } else {
                        stringResource(R.string.search_unranked_short, LETTERS[i])
                    }
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        hit.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
    }
}

/** A cosine similarity as shown: two decimal places, never a percentage, since a good match can be far from 1. */
private fun Float.twoPlaces() = "%.2f".format(this)

/** The model's name, numbered when two spaces share it (one model with two preprocessings). */
private fun spaceLabels(spaces: List<SpaceChoice>): Map<String, String> {
    val counts = spaces.groupingBy { it.space.modelName }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return spaces.associate { (space, _) ->
        val n = seen.merge(space.modelName, 1, Int::plus)!!
        space.spaceId to if (counts.getValue(space.modelName) > 1) "${space.modelName} ($n)" else space.modelName
    }
}

private val PICKABLE = setOf(SpaceState.READY, SpaceState.BUILDING, SpaceState.VERIFYING)

/** Letters for the compared models, in the order they were searched. */
private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

private fun letter(ui: SearchUi, spaceId: String): String? =
    ui.results?.searched?.takeIf { ui.compare && it.size > 1 }?.indexOfFirst { it.space.spaceId == spaceId }?.takeIf { it >= 0 }?.let { LETTERS[it].toString() }

private fun EmbeddingSpace.badge(coverage: Float?): BadgeStatus? = when (state) {
    SpaceState.READY -> null
    SpaceState.BUILDING, SpaceState.VERIFYING -> BadgeStatus.Building(((coverage ?: 0f) * 100).toInt())
    SpaceState.PLANNED -> BadgeStatus.NotIndexed
    SpaceState.STALE -> BadgeStatus.Stale
    SpaceState.FAILED -> BadgeStatus.Failed()
    SpaceState.DELETING -> BadgeStatus.Deleting
}

private fun Evidence.label() = when (this) {
    Evidence.MEANING -> R.string.search_by_meaning
    Evidence.TEXT -> R.string.search_by_text
    Evidence.MEMO -> R.string.search_by_memo
    Evidence.PLACE -> R.string.search_place
    Evidence.PLACE_INFERRED -> R.string.search_place_inferred
    Evidence.PLACE_HISTORY -> R.string.search_place_history
}

internal fun TimelineItem.kindLabel() = if (kind == MediaKind.VIDEO) R.string.search_video_scene else R.string.search_photo

private fun previewSpace(id: String, state: SpaceState, isDefault: Boolean) =
    EmbeddingSpace(id, "EmbeddingGemma 2", state, id, isDefault, "m", 768, "L2", null, null, id)

private val previewHits = listOf(
    SearchHit(TimelineItem("p", MediaKind.IMAGE, "", 0), null, setOf(Evidence.MEANING, Evidence.TEXT), "丸の内北口", mapOf("a" to 1, "b" to 3), mapOf("a" to 0.42f, "b" to 0.37f)),
    SearchHit(TimelineItem("v", MediaKind.VIDEO, "", 0, durationMs = 95_000), 42_000, setOf(Evidence.MEANING), null, mapOf("a" to 2), mapOf("a" to 0.39f)),
)

@IrohacoPreviews
@Composable
private fun SearchResultsPreview() {
    val spaces = listOf(
        SpaceChoice(previewSpace("a", SpaceState.READY, isDefault = true), null),
        SpaceChoice(previewSpace("b", SpaceState.BUILDING, isDefault = false), 0.42f),
    )
    IrohacoTheme(dynamicColor = false) {
        SearchContent(
            SearchUi(query = "駅", compare = true, results = SearchResults(previewHits, spaces, meaningUnavailable = false)),
            spaces, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@IrohacoPreviews
@Composable
private fun SearchNoIndexPreview() {
    IrohacoTheme(dynamicColor = false) {
        SearchContent(SearchUi(query = "駅", results = SearchResults(emptyList(), emptyList(), meaningUnavailable = true)), emptyList(), {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** The way into the chat with AI, set apart from the search above it. */
@Composable
private fun AskEntry(onOpenAsk: () -> Unit) {
    Card(
        onClick = onOpenAsk,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = IrohacoSpacing.s),
    ) {
        Row(Modifier.padding(IrohacoSpacing.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.m)) {
            Icon(painterResource(IrohacoIcons.SmartToy), contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.search_ask_entry), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(painterResource(IrohacoIcons.ChevronRight), contentDescription = null)
        }
    }
}
