package dev.irohaco.feature.timeline

import androidx.compose.foundation.layout.Arrangement
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
    IrohacoBottomSheet(onDismiss = onDismiss) { SortContent(query, onChange, grouping, onGrouping, tags) }
}

private val GROUPINGS = listOf(
    DateGrouping.YEAR to R.string.timeline_group_year,
    DateGrouping.MONTH to R.string.timeline_group_month,
    DateGrouping.DAY to R.string.timeline_group_day,
)

private val SORTS = listOf(
    Triple(TimelineSortKey.TAKEN, true, R.string.timeline_sort_newest),
    Triple(TimelineSortKey.TAKEN, false, R.string.timeline_sort_oldest),
    Triple(TimelineSortKey.NAME, false, R.string.timeline_sort_name_asc),
    Triple(TimelineSortKey.NAME, true, R.string.timeline_sort_name_desc),
    Triple(TimelineSortKey.SIZE, true, R.string.timeline_sort_largest),
    Triple(TimelineSortKey.SIZE, false, R.string.timeline_sort_smallest),
    Triple(TimelineSortKey.RATING, true, R.string.timeline_sort_rating_high),
    Triple(TimelineSortKey.RATING, false, R.string.timeline_sort_rating_low),
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
        // Date headers exist only when sorted by date, so their unit is offered only then.
        if (query.sortKey == TimelineSortKey.TAKEN) {
            Text(
                stringResource(R.string.timeline_group_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = IrohacoSpacing.l, end = IrohacoSpacing.l, top = IrohacoSpacing.s),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                GROUPINGS.forEachIndexed { index, (unit, label) ->
                    ToggleButton(
                        checked = grouping == unit,
                        onCheckedChange = { onGrouping(unit) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            GROUPINGS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        Column(Modifier.selectableGroup().padding(bottom = IrohacoSpacing.l)) {
            SORTS.forEach { (key, descending, label) ->
                val selected = query.sortKey == key && query.descending == descending
                ListItem(
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) {
                        onChange(query.copy(sortKey = key, descending = descending))
                    },
                ) { Text(stringResource(label)) }
            }
        }
    }
}

@IrohacoPreviews
@Composable
private fun SortContentPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { SortContent(TimelineQuery(kind = MediaKind.IMAGE, favoritesOnly = true, tagIds = setOf("t")), {}, DateGrouping.MONTH, tags = listOf(Tag("t", "海", 3), Tag("u", "山", 1))) }
    }
}
