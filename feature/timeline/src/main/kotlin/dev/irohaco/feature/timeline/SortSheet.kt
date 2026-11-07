package dev.irohaco.feature.timeline

import dev.irohaco.feature.timeline.R
import dev.irohaco.core.domain.GeoPoint
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.location.LocationManager
import android.content.pm.PackageManager
import android.content.Context
import android.annotation.SuppressLint
import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.irohaco.core.contracts.DateGrouping
import dev.irohaco.core.contracts.Tag
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineSortKey
import dev.irohaco.core.designsystem.component.IrohacoBottomSheet
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.search.DateRangeDialog
import dev.irohaco.feature.timeline.search.dateRangeLabel

@Composable
internal fun SortSheet(
    query: TimelineQuery,
    onChange: (TimelineQuery) -> Unit,
    onDismiss: () -> Unit,
    grouping: DateGrouping = DateGrouping.DAY,
    onGrouping: (DateGrouping) -> Unit = {},
    tags: List<Tag> = emptyList(),
) {
    val context = LocalContext.current
    // The distance order waits for where the device is; asking for the permission first when it is not granted.
    var waiting by remember { mutableStateOf<TimelineQuery?>(null) }
    val locate = { next: TimelineQuery -> currentPlace(context) { here -> onChange(next.copy(near = here)) } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        waiting?.let { if (granted) locate(it) }
        waiting = null
    }
    IrohacoBottomSheet(onDismiss = onDismiss) {
        SortContent(query, onChange, grouping, onGrouping, tags) { next ->
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locate(next)
            } else {
                waiting = next
                ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }
}

/**
 * Where the device is now, read once on the device and passed to [onPlace]; nothing when it cannot tell. The place is
 * only used to order the grid and is not kept or sent anywhere.
 */
@SuppressLint("MissingPermission") // checked by the caller
private fun currentPlace(context: Context, onPlace: (GeoPoint) -> Unit) {
    val manager = context.getSystemService(LocationManager::class.java) ?: return
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { manager.hasProvider(it) && manager.isProviderEnabled(it) } ?: return
    manager.getCurrentLocation(provider, null, context.mainExecutor) { found ->
        (found ?: manager.getLastKnownLocation(provider))?.let { onPlace(GeoPoint(it.latitude, it.longitude)) }
    }
}

/** The keys to sort by, and the labels of each one's two directions: descending first. */
private val KEYS = listOf(
    SortKeyChoice(TimelineSortKey.TAKEN, R.string.timeline_sort_taken, R.string.timeline_sort_newest, R.string.timeline_sort_oldest, descendingFirst = true),
    SortKeyChoice(TimelineSortKey.DISTANCE, R.string.timeline_sort_distance, R.string.timeline_sort_farthest, R.string.timeline_sort_nearest, descendingFirst = false),
    SortKeyChoice(TimelineSortKey.NAME, R.string.timeline_sort_name, R.string.timeline_sort_name_desc, R.string.timeline_sort_name_asc, descendingFirst = false),
    SortKeyChoice(TimelineSortKey.SIZE, R.string.timeline_sort_size, R.string.timeline_sort_largest, R.string.timeline_sort_smallest, descendingFirst = true),
    SortKeyChoice(TimelineSortKey.RATING, R.string.timeline_sort_rating, R.string.timeline_sort_rating_high, R.string.timeline_sort_rating_low, descendingFirst = true),
)

/** [descendingFirst] is the direction the key starts in, and the one shown first. */
private class SortKeyChoice(val key: TimelineSortKey, val label: Int, val descending: Int, val ascending: Int, val descendingFirst: Boolean)

private val GROUPINGS = listOf(
    DateGrouping.YEAR to R.string.timeline_group_year,
    DateGrouping.MONTH to R.string.timeline_group_month,
    DateGrouping.DAY to R.string.timeline_group_day,
)

private val KINDS = listOf(null to R.string.timeline_kind_all, MediaKind.IMAGE to R.string.timeline_kind_photos, MediaKind.VIDEO to R.string.timeline_kind_videos)

/**
 * Narrows the grid by kind, favorites, tags and the dates taken, then picks one of the sort orders
 * (GAL-006, DESIGN §3). The viewer opened from the grid follows the same filter.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun SortContent(
    query: TimelineQuery,
    onChange: (TimelineQuery) -> Unit,
    grouping: DateGrouping = DateGrouping.DAY,
    onGrouping: (DateGrouping) -> Unit = {},
    tags: List<Tag> = emptyList(),
    /** Sorts by distance once the device's place is known; [TimelineQuery.near] is filled in by the caller. */
    onDistance: (TimelineQuery) -> Unit = onChange,
) {
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    if (pickingDates) {
        DateRangeDialog(query.takenFromMs, query.takenUntilMs, onDismiss = { pickingDates = false }) { from, until ->
            pickingDates = false
            onChange(query.copy(takenFromMs = from, takenUntilMs = until))
        }
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(end = IrohacoSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.timeline_filter),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
            )
            if (query.filtered) TextButton(onClick = { onChange(query.unfiltered()) }) { Text(stringResource(R.string.timeline_filter_clear)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            KINDS.forEachIndexed { index, (kind, label) ->
                ToggleButton(
                    checked = query.kind == kind,
                    onCheckedChange = { onChange(query.copy(kind = kind)) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        KINDS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(label)) }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
            modifier = Modifier.padding(horizontal = IrohacoSpacing.l),
        ) {
            FilterChip(
                selected = query.favoritesOnly,
                onClick = { onChange(query.copy(favoritesOnly = !query.favoritesOnly)) },
                label = { Text(stringResource(R.string.timeline_filter_favorites)) },
            )
            val from = query.takenFromMs
            val until = query.takenUntilMs
            FilterChip(
                selected = from != null && until != null,
                onClick = { if (from != null && until != null) onChange(query.copy(takenFromMs = null, takenUntilMs = null)) else pickingDates = true },
                label = { Text(if (from != null && until != null) dateRangeLabel(from, until) else stringResource(R.string.search_dates)) },
            )
            TagFilterChips(tags, query.tagIds) { onChange(query.copy(tagIds = it)) }
        }
        Text(
            stringResource(R.string.timeline_sort_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = IrohacoSpacing.l, end = IrohacoSpacing.l, top = IrohacoSpacing.l, bottom = IrohacoSpacing.s),
        )
        // Each key once, as a radio; the chosen one shows its two directions under it, and by date the unit of the headers.
        Column(Modifier.selectableGroup().padding(bottom = IrohacoSpacing.l)) {
            KEYS.forEach { choice ->
                val selected = query.sortKey == choice.key
                ListItem(
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    supportingContent = if (choice.key == TimelineSortKey.DISTANCE) ({ Text(stringResource(R.string.timeline_sort_distance_hint)) }) else null,
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) {
                        if (selected) return@selectable
                        val next = query.copy(sortKey = choice.key, descending = choice.descendingFirst)
                        if (choice.key == TimelineSortKey.DISTANCE) onDistance(next) else onChange(next)
                    },
                ) { Text(stringResource(choice.label)) }
                if (selected) {
                    Column(Modifier.padding(start = SUB_INDENT, end = IrohacoSpacing.l, bottom = IrohacoSpacing.s), verticalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                        val directions = if (choice.descendingFirst) listOf(true to choice.descending, false to choice.ascending) else listOf(false to choice.ascending, true to choice.descending)
                        Connected(directions.map { it.second }, directions.indexOfFirst { it.first == query.descending }) { onChange(query.copy(descending = directions[it].first)) }
                        when (choice.key) {
                            // Date headers exist only when sorted by date, so their unit is offered only then.
                            TimelineSortKey.TAKEN -> {
                                Text(stringResource(R.string.timeline_group_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Connected(GROUPINGS.map { it.second }, GROUPINGS.indexOfFirst { it.first == grouping }) { onGrouping(GROUPINGS[it].first) }
                            }
                            TimelineSortKey.DISTANCE -> ListItem(
                                supportingContent = { Text(stringResource(R.string.search_inferred_places_hint)) },
                                trailingContent = { Switch(checked = query.nearIncludesInferred, onCheckedChange = null) },
                                modifier = Modifier.toggleable(query.nearIncludesInferred, role = Role.Switch) { onChange(query.copy(nearIncludesInferred = it)) },
                            ) { Text(stringResource(R.string.search_inferred_places)) }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** One choice of a few, as connected buttons. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Connected(labels: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        labels.forEachIndexed { index, label ->
            ToggleButton(
                checked = index == selected,
                onCheckedChange = { onSelect(index) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(label)) }
        }
    }
}

/** Under the radio's text, so the options read as the chosen key's own. */
private val SUB_INDENT = 56.dp

/**
 * What the grid is narrowed by, under the bar, so photos that do not show are never a mystery; each
 * chip takes its own condition off, and the last button all of them.
 */
@Composable
internal fun ActiveFilters(query: TimelineQuery, tags: List<Tag>, onChange: (TimelineQuery) -> Unit, onEdit: () -> Unit) {
    if (!query.filtered) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = IrohacoSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        query.kind?.let { kind ->
            FilterOff(stringResource(KINDS.first { it.first == kind }.second)) { onChange(query.copy(kind = null)) }
        }
        if (query.favoritesOnly) FilterOff(stringResource(R.string.timeline_filter_favorites)) { onChange(query.copy(favoritesOnly = false)) }
        val from = query.takenFromMs
        val until = query.takenUntilMs
        if (from != null && until != null) FilterOff(dateRangeLabel(from, until)) { onChange(query.copy(takenFromMs = null, takenUntilMs = null)) }
        tags.filter { it.tagId in query.tagIds }.forEach { tag -> FilterOff(tag.name) { onChange(query.copy(tagIds = query.tagIds - tag.tagId)) } }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.timeline_filter_edit)) }
        TextButton(onClick = { onChange(query.unfiltered()) }) { Text(stringResource(R.string.timeline_filter_clear)) }
    }
}

@Composable
private fun FilterOff(label: String, onOff: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onOff,
        label = { Text(label) },
        trailingIcon = { Icon(painterResource(IrohacoIcons.Close), stringResource(R.string.timeline_filter_off, label)) },
    )
}

@IrohacoPreviews
@Composable
private fun SortContentPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { SortContent(TimelineQuery(kind = MediaKind.IMAGE, favoritesOnly = true, tagIds = setOf("t")), {}, DateGrouping.MONTH, tags = listOf(Tag("t", "海", 3), Tag("u", "山", 1))) }
    }
}

@IrohacoPreviews
@Composable
private fun ActiveFiltersPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { ActiveFilters(TimelineQuery(favoritesOnly = true, tagIds = setOf("t", "u")), listOf(Tag("t", "海", 3), Tag("u", "山", 1)), {}, {}) }
    }
}
