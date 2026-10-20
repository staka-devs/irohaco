package dev.irohaco.feature.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.contracts.TimelineSortKey
import dev.irohaco.core.designsystem.component.IrohacoBottomSheet
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind

@Composable
internal fun SortSheet(query: TimelineQuery, onChange: (TimelineQuery) -> Unit, onDismiss: () -> Unit) {
    IrohacoBottomSheet(onDismiss = onDismiss) { SortContent(query, onChange) }
}

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

/** Filter by kind with a connected button group, then one of the sort orders (GAL-006, DESIGN §3). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SortContent(query: TimelineQuery, onChange: (TimelineQuery) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Text(
            stringResource(R.string.timeline_sort_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
        )
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
        Column(Modifier.selectableGroup().padding(bottom = IrohacoSpacing.l)) {
            SORTS.forEach { (key, descending, label) ->
                val selected = query.sortKey == key && query.descending == descending
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) {
                        onChange(query.copy(sortKey = key, descending = descending))
                    },
                )
            }
        }
    }
}

@IrohacoPreviews
@Composable
private fun SortContentPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { SortContent(TimelineQuery(TimelineSortKey.NAME, descending = false, kind = MediaKind.IMAGE), {}) }
    }
}
