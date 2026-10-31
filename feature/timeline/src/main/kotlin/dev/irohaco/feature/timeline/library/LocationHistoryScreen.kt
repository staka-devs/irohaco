package dev.irohaco.feature.timeline.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.HistoryGroup
import dev.irohaco.core.application.LocationHistory
import dev.irohaco.core.contracts.HistoryReview
import dev.irohaco.core.contracts.KnownPlace
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.contracts.UnreadableHistoryException.Reason
import dev.irohaco.core.designsystem.component.GridThumbnail
import dev.irohaco.core.designsystem.component.MediaThumbnail
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.GeoPoint
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.R
import dev.irohaco.feature.timeline.rememberDateFormatter
import dev.irohaco.feature.timeline.search.DateRangeDialog
import dev.irohaco.feature.timeline.search.dateRangeLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.time.LocalDate

class LocationHistoryViewModel(
    private val history: LocationHistory,
    /** Whether OpenFreeMap may receive a place, so a card can show it on a map (ADR-0011). */
    mapAllowed: Flow<Boolean> = flowOf(false),
    /** The photos picked in the timeline, whose candidates alone are shown; null shows all (#213). */
    only: Set<String>? = null,
) : ViewModel() {
    /** By place across days unless the user splits them by day (#220). */
    private val _byDay = MutableStateFlow(false)
    val byDay: StateFlow<Boolean> = _byDay.asStateFlow()

    /** The days the photos were taken, start and end of whole local days; null shows all (#220). */
    private val _dates = MutableStateFlow<Pair<Long, Long>?>(null)
    val dates: StateFlow<Pair<Long, Long>?> = _dates.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val groups: StateFlow<List<HistoryGroup>> = combine(_byDay, _dates) { byDay, dates -> byDay to dates }
        .flatMapLatest { (byDay, dates) -> history.groups(only, byDay, dates?.first, dates?.second) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The candidates, or the photos without a place and without a candidate (#220). */
    private val _tab = MutableStateFlow(HistoryTab.CANDIDATES)
    val tab: StateFlow<HistoryTab> = _tab.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val unmatched: StateFlow<List<TimelineItem>> = _dates
        .flatMapLatest { dates -> history.unmatched(dates?.first, dates?.second) }
        .map { items -> if (only == null) items else items.filter { it.assetId in only } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val places: StateFlow<List<KnownPlace>> = history.places().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mapAllowed: StateFlow<Boolean> = mapAllowed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Photos picked in a group, so its buttons decide just those (ADR-0029). */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    /** null before an import; [importing] while one runs. */
    private val _imported = MutableStateFlow<LocationHistory.Imported?>(null)
    val imported: StateFlow<LocationHistory.Imported?> = _imported.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun import(contentUri: String) {
        _importing.value = true
        viewModelScope.launch {
            _imported.value = history.import(contentUri)
            _importing.value = false
        }
    }

    fun toggle(assetId: String) = _selection.update { if (assetId in it) it - assetId else it + assetId }

    fun toggleByDay() = _byDay.update { !it }

    /** A pick in one tab means nothing in the other, so it is dropped. */
    fun showTab(tab: HistoryTab) {
        _tab.value = tab
        _selection.value = emptySet()
    }

    /** Gives the picked photos [point] as their place. */
    fun place(point: GeoPoint) {
        val picked = _selection.value
        _selection.value = emptySet()
        viewModelScope.launch { history.place(picked, point) }
    }

    /** null clears the dates. */
    fun showDates(fromMs: Long?, untilMs: Long?) {
        _dates.value = if (fromMs != null && untilMs != null) fromMs to untilMs else null
    }

    /** The photos picked in [group], or all of it when none are. */
    fun decide(group: HistoryGroup, confirmed: Boolean) {
        val picked = group.reviews.filter { it.item.assetId in _selection.value }.ifEmpty { group.reviews }
        _selection.update { it - picked.map { review -> review.item.assetId }.toSet() }
        viewModelScope.launch { history.decide(picked, confirmed) }
    }
}

enum class HistoryTab { CANDIDATES, UNMATCHED }

/**
 * Places from the location history the user exported, confirmed or rejected by place, or by day and place;
 * and the photos without any, given a place other photos have (#174, #220, ADR-0029).
 */
@Composable
fun LocationHistoryScreen(viewModel: LocationHistoryViewModel, onBack: () -> Unit) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val mapAllowed by viewModel.mapAllowed.collectAsStateWithLifecycle()
    val byDay by viewModel.byDay.collectAsStateWithLifecycle()
    val dates by viewModel.dates.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val unmatched by viewModel.unmatched.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    // The export has no fixed type: a phone may label it JSON, text or a plain file.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.import(it.toString()) } }
    LocationHistoryContent(
        groups, selection, imported, importing, onBack, onImport = { pick.launch(arrayOf("*/*")) }, viewModel::toggle, viewModel::decide,
        byDay, dates, viewModel::toggleByDay, viewModel::showDates,
        tab, viewModel::showTab, unmatched, places, viewModel::place,
    ) { point ->
        if (mapAllowed) MapPreview(point)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LocationHistoryContent(
    groups: List<HistoryGroup>,
    selection: Set<String>,
    imported: LocationHistory.Imported?,
    importing: Boolean,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onToggle: (String) -> Unit,
    onDecide: (HistoryGroup, Boolean) -> Unit,
    byDay: Boolean = false,
    dates: Pair<Long, Long>? = null,
    onToggleByDay: () -> Unit = {},
    onDates: (Long?, Long?) -> Unit = { _, _ -> },
    tab: HistoryTab = HistoryTab.CANDIDATES,
    onTab: (HistoryTab) -> Unit = {},
    unmatched: List<TimelineItem> = emptyList(),
    places: List<KnownPlace> = emptyList(),
    onPlace: (GeoPoint) -> Unit = {},
    /** The groups shown open at first, by [groupKey]; a preview opens one. */
    opened: List<String> = emptyList(),
    map: @Composable (GeoPoint) -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(opened) }
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    if (pickingDates) DateRangeDialog(dates?.first, dates?.second, onDismiss = { pickingDates = false }) { from, until -> pickingDates = false; onDates(from, until) }
    var pickingPlace by rememberSaveable { mutableStateOf(false) }
    if (pickingPlace) PlacePickerDialog(places, onDismiss = { pickingPlace = false }) { pickingPlace = false; onPlace(it.point) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) } },
            )
        },
        bottomBar = {
            if (tab == HistoryTab.UNMATCHED && selection.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.navigationBarsPadding()) {
                    Button(onClick = { pickingPlace = true }, modifier = Modifier.fillMaxWidth().padding(IrohacoSpacing.l)) {
                        Text(pluralStringResource(R.plurals.history_place_picked, selection.size, selection.size))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = IrohacoSpacing.l, end = IrohacoSpacing.l, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + IrohacoSpacing.l),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                    Text(stringResource(R.string.history_explain), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onImport, enabled = !importing) { Text(stringResource(R.string.history_import)) }
                    if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    imported?.let { Text(importedText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    SecondaryTabRow(selectedTabIndex = tab.ordinal) {
                        Tab(selected = tab == HistoryTab.CANDIDATES, onClick = { onTab(HistoryTab.CANDIDATES) }, text = { Text(stringResource(R.string.history_tab_candidates)) })
                        Tab(selected = tab == HistoryTab.UNMATCHED, onClick = { onTab(HistoryTab.UNMATCHED) }, text = { Text(stringResource(R.string.history_tab_unmatched)) })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                        if (tab == HistoryTab.CANDIDATES) {
                            FilterChip(byDay, onToggleByDay, label = { Text(stringResource(R.string.history_by_day)) })
                        }
                        val label = dates?.let { (from, until) -> dateRangeLabel(from, until) }
                        FilterChip(label != null, { if (label != null) onDates(null, null) else pickingDates = true }, label = { Text(label ?: stringResource(R.string.search_dates)) })
                    }
                    when {
                        importing -> Unit
                        tab == HistoryTab.CANDIDATES && groups.isEmpty() -> Text(stringResource(R.string.history_none), style = MaterialTheme.typography.bodyMedium)
                        tab == HistoryTab.UNMATCHED && unmatched.isEmpty() -> Text(stringResource(R.string.history_no_unmatched), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            when {
                tab == HistoryTab.UNMATCHED -> items(unmatched.chunked(UNMATCHED_COLUMNS), key = { it.first().assetId }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
                        row.forEach { item ->
                            MediaThumbnail(
                                model = GridThumbnail(item.contentUri),
                                contentDescription = stringResource(if (item.kind == MediaKind.VIDEO) R.string.search_video_scene else R.string.search_photo),
                                selected = item.assetId in selection,
                                modifier = Modifier.weight(1f).aspectRatio(1f).clickable { onToggle(item.assetId) },
                            )
                        }
                        // The last row keeps the cells the same size as the others.
                        repeat(UNMATCHED_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                else -> items(groups, key = ::groupKey) { group ->
                    val key = groupKey(group)
                    HistoryGroupItem(group, key in open, { open = if (key in open) open - key else open + key }, selection, onToggle, onDecide, map)
                }
            }
        }
    }
}

@Composable
private fun importedText(imported: LocationHistory.Imported): String = when (imported) {
    is LocationHistory.Imported.Matched -> pluralStringResource(R.plurals.history_matched, imported.count, imported.count)
    is LocationHistory.Imported.Unreadable -> stringResource(
        when (imported.reason) {
            Reason.NOT_JSON -> R.string.history_not_json
            Reason.UNKNOWN_FORMAT -> R.string.history_unknown_format
            Reason.EMPTY -> R.string.history_empty
        },
    )
}

private fun groupKey(group: HistoryGroup) = "${group.from}-${group.reviews.first().item.assetId}"

/**
 * A group as one line: its first photo, the place, the count and the days. Swiping the line right confirms it
 * and left rejects it. Tapping the line opens the rest below it: the whole place and days, how sure the place
 * is, the map, every photo to pick, and the buttons, so a swipe or the buttons decide just the picked ones (#220).
 */
@Composable
private fun HistoryGroupItem(
    group: HistoryGroup,
    expanded: Boolean,
    onExpand: () -> Unit,
    selection: Set<String>,
    onToggle: (String) -> Unit,
    onDecide: (HistoryGroup, Boolean) -> Unit,
    map: @Composable (GeoPoint) -> Unit,
) {
    val formatter = rememberDateFormatter()
    val picked = group.reviews.count { it.item.assetId in selection }
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val reject = stringResource(R.string.history_reject)
    val confirm = stringResource(R.string.history_confirm)
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        SwipeToDismissBox(
            swipe,
            backgroundContent = {
                val toConfirm = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(if (toConfirm) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = IrohacoSpacing.m),
                    horizontalArrangement = if (toConfirm) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(if (toConfirm) IrohacoIcons.Check else IrohacoIcons.Close), null)
                }
            },
            onDismiss = { direction ->
                onDecide(group, direction == SwipeToDismissBoxValue.StartToEnd)
                // A group with photos left stays in the list, so its line comes back.
                scope.launch { swipe.reset() }
            },
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClickLabel = stringResource(if (expanded) R.string.history_collapse else R.string.history_expand), onClick = onExpand)
                // A swipe has no button to find, so the same choices are actions for a screen reader.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(confirm) { onDecide(group, true); true },
                        CustomAccessibilityAction(reject) { onDecide(group, false); true },
                    )
                }
                .padding(vertical = IrohacoSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaThumbnail(
                model = GridThumbnail(group.reviews.first().item.contentUri),
                contentDescription = stringResource(R.string.search_photo),
                selected = false,
                modifier = Modifier.size(48.dp),
            )
            Column(Modifier.weight(1f)) {
                // Open, the line shows the whole place and days instead of cutting them off.
                val lines = if (expanded) Int.MAX_VALUE else 1
                Text(group.placeName ?: "%.4f, %.4f".format(group.point.latitude, group.point.longitude), style = MaterialTheme.typography.titleSmall, maxLines = lines, overflow = TextOverflow.Ellipsis)
                Text(
                    // The count first, so a long range of days cannot push it off the line.
                    listOf(
                        pluralStringResource(R.plurals.history_count, group.reviews.size, group.reviews.size),
                        if (group.from == group.to) formatter.format(group.from) else "${formatter.format(group.from)} – ${formatter.format(group.to)}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(painterResource(IrohacoIcons.ExpandMore), null, Modifier.rotate(if (expanded) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
        if (expanded) {
            Column(Modifier.padding(bottom = IrohacoSpacing.s), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                Text(stringResource(confidenceLabel(group.confidence)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                map(group.point)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.xxs)) {
                    items(group.reviews, key = { it.item.assetId }) { review ->
                        MediaThumbnail(
                            model = GridThumbnail(review.item.contentUri),
                            contentDescription = stringResource(if (review.item.kind == MediaKind.VIDEO) R.string.search_video_scene else R.string.search_photo),
                            selected = review.item.assetId in selection,
                            modifier = Modifier.size(72.dp).clickable { onToggle(review.item.assetId) },
                        )
                    }
                }
                // With photos picked, the buttons say how many they decide.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onDecide(group, false) }) { Text(if (picked > 0) pluralStringResource(R.plurals.history_reject_picked, picked, picked) else reject) }
                    FilledTonalButton(onClick = { onDecide(group, true) }) { Text(if (picked > 0) pluralStringResource(R.plurals.history_confirm_picked, picked, picked) else confirm) }
                }
            }
        }
        HorizontalDivider()
    }
}

/** Picks a place other photos have, or a named place of a candidate; typing narrows the list by name (#220). */
@Composable
private fun PlacePickerDialog(places: List<KnownPlace>, onDismiss: () -> Unit, onPick: (KnownPlace) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val shown = places.filter { it.name.contains(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_pick_place)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text(stringResource(R.string.history_place_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.history_no_places), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.name }) { place ->
                            ListItem(
                                supportingContent = { Text(pluralStringResource(R.plurals.history_count, place.count, place.count)) },
                                modifier = Modifier.clickable { onPick(place) },
                            ) { Text(place.name) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_cancel)) } },
    )
}

private const val UNMATCHED_COLUMNS = 4

/**
 * The place as a still image of the map, with a pin at its center. A snapshot instead of a live map,
 * since a list of cards would otherwise hold a map view each; only the shown area goes to OpenFreeMap.
 */
@Composable
private fun MapPreview(point: GeoPoint) {
    val context = LocalContext.current
    var image by remember(point) { mutableStateOf<ImageBitmap?>(null) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(MAP_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val width = maxWidth.value.toInt()
        val density = LocalDensity.current.density
        DisposableEffect(point, width) {
            MapLibre.getInstance(context)
            val options = MapSnapshotter.Options(width, MAP_HEIGHT.value.toInt())
                .withPixelRatio(density)
                .withStyleBuilder(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE))
                .withCameraPosition(CameraPosition.Builder().target(LatLng(point.latitude, point.longitude)).zoom(MAP_ZOOM).build())
            val snapshotter = MapSnapshotter(context, options)
            snapshotter.start({ image = it.bitmap.asImageBitmap() }, { /* the card still shows the place name */ })
            onDispose { snapshotter.cancel() }
        }
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Icon(
            painterResource(IrohacoIcons.LocationOn),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).offset(y = -PIN_SIZE / 2).size(PIN_SIZE),
        )
    }
}

private val MAP_HEIGHT = 120.dp
private val PIN_SIZE = 32.dp
private const val MAP_ZOOM = 14.0

/** How sure the place is, in words: a stay is sure, a point near in time less so. */
private fun confidenceLabel(confidence: Double) = when {
    confidence >= LocationHistory.IN_VISIT -> R.string.history_confidence_high
    confidence >= LocationHistory.NEAR_POINT -> R.string.history_confidence_medium
    else -> R.string.history_confidence_low
}

@IrohacoPreviews
@Composable
private fun LocationHistoryUnmatchedPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            LocationHistoryContent(
                groups = emptyList(),
                selection = setOf("b", "c"),
                imported = null,
                importing = false,
                onBack = {},
                onImport = {},
                onToggle = {},
                onDecide = { _, _ -> },
                tab = HistoryTab.UNMATCHED,
                unmatched = ('a'..'f').map { TimelineItem(it.toString(), MediaKind.IMAGE, "content://$it", 0) },
            )
        }
    }
}

@IrohacoPreviews
@Composable
private fun LocationHistoryPreview() {
    fun review(id: String, point: GeoPoint, name: String?, confidence: Double) =
        HistoryReview(TimelineItem(id, MediaKind.IMAGE, "content://$id", 0), point, name, confidence)
    val kyoto = GeoPoint(35.0116, 135.7681)
    val road = GeoPoint(34.9858, 135.7588)
    IrohacoTheme(dynamicColor = false) {
        Surface {
            LocationHistoryContent(
                groups = listOf(
                    HistoryGroup(LocalDate.of(2024, 8, 3), LocalDate.of(2024, 8, 5), "Kyoto Station", kyoto, 0.9, listOf(review("a", kyoto, "Kyoto Station", 0.9), review("b", kyoto, "Kyoto Station", 0.9))),
                    HistoryGroup(LocalDate.of(2024, 8, 3), LocalDate.of(2024, 8, 3), null, road, 0.5, listOf(review("c", road, null, 0.5))),
                ),
                selection = setOf("b"),
                opened = listOf("2024-08-03-a"),
                imported = LocationHistory.Imported.Matched(3),
                importing = false,
                onBack = {},
                onImport = {},
                onToggle = {},
                onDecide = { _, _ -> },
            )
        }
    }
}
