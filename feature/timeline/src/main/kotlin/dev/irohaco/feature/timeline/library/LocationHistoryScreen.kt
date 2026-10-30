package dev.irohaco.feature.timeline.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.irohaco.core.application.HistoryGroup
import dev.irohaco.core.application.LocationHistory
import dev.irohaco.core.contracts.HistoryReview
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val groups: StateFlow<List<HistoryGroup>> = history.groups(only).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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

    /** The photos picked in [group], or all of it when none are. */
    fun decide(group: HistoryGroup, confirmed: Boolean) {
        val picked = group.reviews.filter { it.item.assetId in _selection.value }.ifEmpty { group.reviews }
        _selection.update { it - picked.map { review -> review.item.assetId }.toSet() }
        viewModelScope.launch { history.decide(picked, confirmed) }
    }
}

/** Places from the location history the user exported, confirmed or rejected by day and place (#174, ADR-0029). */
@Composable
fun LocationHistoryScreen(viewModel: LocationHistoryViewModel, onBack: () -> Unit) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val mapAllowed by viewModel.mapAllowed.collectAsStateWithLifecycle()
    // The export has no fixed type: a phone may label it JSON, text or a plain file.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.import(it.toString()) } }
    LocationHistoryContent(groups, selection, imported, importing, onBack, onImport = { pick.launch(arrayOf("*/*")) }, viewModel::toggle, viewModel::decide) { point ->
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
    map: @Composable (GeoPoint) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(IrohacoIcons.ArrowBack), stringResource(R.string.viewer_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = IrohacoSpacing.l, end = IrohacoSpacing.l, top = padding.calculateTopPadding(), bottom = IrohacoSpacing.l),
            verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                    Text(stringResource(R.string.history_explain), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onImport, enabled = !importing) { Text(stringResource(R.string.history_import)) }
                    if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    imported?.let { Text(importedText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (groups.isEmpty() && !importing) Text(stringResource(R.string.history_none), style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(groups, key = { "${it.date}-${it.reviews.first().item.assetId}" }) { group ->
                HistoryGroupCard(group, selection, onToggle, onDecide, map)
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

@Composable
private fun HistoryGroupCard(
    group: HistoryGroup,
    selection: Set<String>,
    onToggle: (String) -> Unit,
    onDecide: (HistoryGroup, Boolean) -> Unit,
    map: @Composable (GeoPoint) -> Unit,
) {
    val formatter = rememberDateFormatter()
    val picked = group.reviews.count { it.item.assetId in selection }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(IrohacoSpacing.l), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
            Text(group.placeName ?: "%.4f, %.4f".format(group.point.latitude, group.point.longitude), style = MaterialTheme.typography.titleMedium)
            Text(
                listOf(
                    formatter.format(group.date),
                    pluralStringResource(R.plurals.history_count, group.reviews.size, group.reviews.size),
                    stringResource(confidenceLabel(group.confidence)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDecide(group, false) }) {
                    Text(if (picked == 0) stringResource(R.string.history_reject) else pluralStringResource(R.plurals.history_reject_picked, picked, picked))
                }
                FilledTonalButton(onClick = { onDecide(group, true) }) {
                    Text(if (picked == 0) stringResource(R.string.history_confirm) else pluralStringResource(R.plurals.history_confirm_picked, picked, picked))
                }
            }
        }
    }
}

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
private fun LocationHistoryPreview() {
    fun review(id: String, point: GeoPoint, name: String?, confidence: Double) =
        HistoryReview(TimelineItem(id, MediaKind.IMAGE, "content://$id", 0), point, name, confidence)
    val kyoto = GeoPoint(35.0116, 135.7681)
    val road = GeoPoint(34.9858, 135.7588)
    IrohacoTheme(dynamicColor = false) {
        Surface {
            LocationHistoryContent(
                groups = listOf(
                    HistoryGroup(LocalDate.of(2024, 8, 3), "Kyoto Station", kyoto, 0.9, listOf(review("a", kyoto, "Kyoto Station", 0.9), review("b", kyoto, "Kyoto Station", 0.9))),
                    HistoryGroup(LocalDate.of(2024, 8, 3), null, road, 0.5, listOf(review("c", road, null, 0.5))),
                ),
                selection = setOf("b"),
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
