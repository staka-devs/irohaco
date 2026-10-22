package dev.irohaco.feature.timeline.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import dev.irohaco.core.application.Evidence
import dev.irohaco.core.application.SearchHit
import dev.irohaco.core.application.SearchMedia
import dev.irohaco.core.application.SearchResults
import dev.irohaco.core.application.SpaceChoice
import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.SpaceState
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
import dev.irohaco.feature.timeline.badgeIcon
import dev.irohaco.feature.timeline.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The search's conditions and what they found; [results] is null before the first search. */
data class SearchUi(
    val query: String = "",
    val byMeaning: Boolean = true,
    val byText: Boolean = true,
    val kind: MediaKind? = null,
    /** A space picked for this search without making it the default, such as one still being built (AC-005). */
    val spaceId: String? = null,
    val searching: Boolean = false,
    val results: SearchResults? = null,
)

class SearchViewModel(private val search: SearchMedia) : ViewModel() {
    val spaces: StateFlow<List<SpaceChoice>> = search.spaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()

    private var running: Job? = null

    fun search(query: String) = change { it.copy(query = query.trim()) }

    fun toggleMeaning() = change { it.copy(byMeaning = !it.byMeaning) }

    fun toggleText() = change { it.copy(byText = !it.byText) }

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
        _ui.update(edit)
        val ui = _ui.value
        running?.cancel()
        if (ui.query.isEmpty()) {
            _ui.update { it.copy(searching = false, results = null) }
            return
        }
        _ui.update { it.copy(searching = true) }
        running = viewModelScope.launch {
            val results = search(ui.query, ui.spaceId, ui.byMeaning, ui.byText, ui.kind)
            _ui.update { it.copy(searching = false, results = results) }
        }
    }
}

/** Opens a photo, or a video at the matched scene (PlayVideoAt, AC-002). */
@Composable
fun SearchScreen(viewModel: SearchViewModel, onOpenMedia: (String) -> Unit, onPlayAt: (String, Long) -> Unit, onOpenSettings: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    SearchContent(
        ui = ui,
        spaces = spaces,
        onSearch = viewModel::search,
        onToggleMeaning = viewModel::toggleMeaning,
        onToggleText = viewModel::toggleText,
        onKind = viewModel::showKind,
        onSpace = viewModel::pickSpace,
        onOpen = { hit -> hit.timestampMs?.let { onPlayAt(hit.item.assetId, it) } ?: onOpenMedia(hit.item.assetId) },
        onOpenSettings = onOpenSettings,
    )
}

/**
 * Search (DESIGN §4.3, SRCH-001〜004, SRCH-006): a search bar, condition chips, the model switch, a
 * banner when the index does not cover everything, and photos and video scenes in one grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchContent(
    ui: SearchUi,
    spaces: List<SpaceChoice>,
    onSearch: (String) -> Unit,
    onToggleMeaning: () -> Unit,
    onToggleText: () -> Unit,
    onKind: (MediaKind?) -> Unit,
    onSpace: (EmbeddingSpace) -> Unit,
    onOpen: (SearchHit) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(ui.query) }
    Scaffold { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(start = IrohacoSpacing.l, end = IrohacoSpacing.l, top = padding.calculateTopPadding(), bottom = IrohacoSpacing.l),
            horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = text,
                            onQueryChange = { text = it },
                            onSearch = onSearch,
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text(stringResource(R.string.search_placeholder)) },
                            leadingIcon = { Icon(painterResource(IrohacoIcons.Search), null) },
                            trailingIcon = {
                                IconButton(onClick = onOpenSettings) { Icon(painterResource(IrohacoIcons.Settings), stringResource(R.string.timeline_settings)) }
                            },
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {}
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Conditions(ui, spaces, onToggleMeaning, onToggleText, onKind, onSpace)
            }
            ui.results?.let { results ->
                item(span = { GridItemSpan(maxLineSpan) }) { Notices(ui, results, onOpenSettings) }
            }
            if (ui.searching) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val hits = ui.results?.hits.orEmpty()
            items(hits, key = { "${it.item.assetId}@${it.timestampMs}" }) { hit -> HitCell(hit, onOpen) }
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
    onToggleMeaning: () -> Unit,
    onToggleText: () -> Unit,
    onKind: (MediaKind?) -> Unit,
    onSpace: (EmbeddingSpace) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
            FilterChip(ui.byMeaning, onToggleMeaning, label = { Text(stringResource(R.string.search_by_meaning)) })
            FilterChip(ui.byText, onToggleText, label = { Text(stringResource(R.string.search_by_text)) })
            FilterChip(ui.kind == MediaKind.IMAGE, { onKind(if (ui.kind == MediaKind.IMAGE) null else MediaKind.IMAGE) }, label = { Text(stringResource(R.string.search_photos)) })
            FilterChip(ui.kind == MediaKind.VIDEO, { onKind(if (ui.kind == MediaKind.VIDEO) null else MediaKind.VIDEO) }, label = { Text(stringResource(R.string.search_videos)) })
        }
        // The model switch appears once there is a choice (DESIGN §3: a few models side by side).
        if (spaces.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xs)) {
                spaces.forEach { (space, coverage) ->
                    val selected = if (ui.spaceId != null) space.spaceId == ui.spaceId else space.isDefault
                    FilterChip(
                        selected = selected,
                        onClick = { onSpace(space) },
                        enabled = space.state in PICKABLE,
                        label = { Text(space.modelName) },
                        trailingIcon = space.badge(coverage)?.let { { StatusBadge(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Notices(ui: SearchUi, results: SearchResults, onOpenSettings: () -> Unit) {
    val coverage = results.coverage
    when {
        ui.byMeaning && results.meaningUnavailable -> Banner(BadgeStatus.AiNotInstalled, stringResource(R.string.search_no_index)) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.search_open_settings)) }
        }
        coverage != null && coverage < 1f -> Banner(BadgeStatus.LowCoverage, stringResource(R.string.search_coverage, (coverage * 100).toInt()))
    }
}

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
private fun HitCell(hit: SearchHit, onOpen: (SearchHit) -> Unit) {
    val item = hit.item
    val at = hit.timestampMs
    val context = LocalContext.current
    // A scene shows its own frame; the grid's small copy would show the video's first one.
    val model: Any = if (at != null) ImageRequest.Builder(context).data(item.contentUri).videoFrameMillis(at).build() else GridThumbnail(item.contentUri)
    val evidence = hit.evidence.sortedBy { it.ordinal }.map { stringResource(it.label()) }.joinToString(" · ")
    val time = at?.let { formatDuration(it) }
    Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
        MediaThumbnail(
            model = model,
            contentDescription = listOfNotNull(stringResource(item.kindLabel()), time?.let { stringResource(R.string.search_at, it) }, evidence).joinToString(", "),
            selected = false,
            label = time ?: item.durationMs?.let(::formatDuration),
            labelIcon = item.badgeIcon,
            modifier = Modifier.clickable { onOpen(hit) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(evidence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        hit.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
    }
}

private val PICKABLE = setOf(SpaceState.READY, SpaceState.BUILDING, SpaceState.VERIFYING)

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
}

private fun TimelineItem.kindLabel() = if (kind == MediaKind.VIDEO) R.string.search_video_scene else R.string.search_photo

private val previewHits = listOf(
    SearchHit(TimelineItem("p", MediaKind.IMAGE, "", 0), null, setOf(Evidence.MEANING, Evidence.TEXT), "丸の内北口"),
    SearchHit(TimelineItem("v", MediaKind.VIDEO, "", 0, durationMs = 95_000), 42_000, setOf(Evidence.MEANING), null),
)

@IrohacoPreviews
@Composable
private fun SearchResultsPreview() {
    val spaces = listOf(
        SpaceChoice(EmbeddingSpace("a", "EmbeddingGemma 2", SpaceState.READY, "s", null, isDefault = true), null),
        SpaceChoice(EmbeddingSpace("b", "EmbeddingGemma 2", SpaceState.BUILDING, "s2", null, isDefault = false), 0.42f),
    )
    IrohacoTheme(dynamicColor = false) {
        SearchContent(
            SearchUi(query = "駅", spaceId = "b", results = SearchResults(previewHits, spaces[1].space, 0.42f, meaningUnavailable = false)),
            spaces, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@IrohacoPreviews
@Composable
private fun SearchNoIndexPreview() {
    IrohacoTheme(dynamicColor = false) {
        SearchContent(SearchUi(query = "駅", results = SearchResults(emptyList(), null, null, meaningUnavailable = true)), emptyList(), {}, {}, {}, {}, {}, {}, {})
    }
}
