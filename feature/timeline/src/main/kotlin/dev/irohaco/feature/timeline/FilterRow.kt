package dev.irohaco.feature.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import dev.irohaco.core.contracts.Tag
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.feature.timeline.search.DateRangeDialog
import dev.irohaco.feature.timeline.search.dateRangeLabel

/**
 * The grid's filters in one row that scrolls sideways, as on the search page: each chip shows what it narrows by, and
 * a chip with a choice opens a menu. The order is in its own sheet. The viewer opened from the grid follows the same filter.
 */
@Composable
internal fun FilterRow(
    query: TimelineQuery,
    tags: List<Tag>,
    onChange: (TimelineQuery) -> Unit,
    modifier: Modifier = Modifier,
    /** The places photos were taken at, with their numbers of files, read when the place chip opens. */
    placeCounts: suspend () -> Map<String, Int> = { emptyMap() },
) {
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    if (pickingDates) {
        DateRangeDialog(
            query.takenFromMs, query.takenUntilMs,
            onDismiss = { pickingDates = false },
            onClear = { pickingDates = false; onChange(query.copy(takenFromMs = null, takenUntilMs = null)) },
        ) { from, until ->
            pickingDates = false
            onChange(query.copy(takenFromMs = from, takenUntilMs = until))
        }
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = IrohacoSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChoiceChip(query.kind, listOf(null, MediaKind.IMAGE, MediaKind.VIDEO), { kindLabel(it) }) { onChange(query.copy(kind = it)) }
        FilterChip(
            selected = query.favoritesOnly,
            onClick = { onChange(query.copy(favoritesOnly = !query.favoritesOnly)) },
            label = { Text(stringResource(R.string.timeline_filter_favorites)) },
            leadingIcon = if (query.favoritesOnly) ({ Icon(painterResource(IrohacoIcons.Check), null) }) else null,
        )
        val from = query.takenFromMs
        val until = query.takenUntilMs
        val dates = if (from != null && until != null) dateRangeLabel(from, until) else null
        FilterChip(
            selected = dates != null,
            onClick = { pickingDates = true },
            label = { Text(dates ?: stringResource(R.string.search_dates)) },
            trailingIcon = { Icon(painterResource(IrohacoIcons.ExpandMore), null) },
        )
        ChoiceChip(query.minRating, listOf(null, 1, 2, 3, 4, 5), { ratingLabel(it) }) { onChange(query.copy(minRating = it)) }
        PlaceChip(
            PlaceChoice(query.place, query.area, query.located),
            placeCounts,
            { onChange(query.copy(place = it.place, area = it.area, located = it.located)) },
            offerLocated = true,
        )
        TagFilterChips(tags, query.tagIds) { onChange(query.copy(tagIds = it)) }
        if (query.filtered) TextButton(onClick = { onChange(query.unfiltered()) }) { Text(stringResource(R.string.timeline_filter_clear)) }
    }
}

/** One of a few values under one chip, picked from its menu; the first value is "any", which leaves the chip unselected. */
@Composable
internal fun <T> ChoiceChip(value: T, values: List<T>, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = value != values.first(),
            onClick = { open = true },
            label = { Text(label(value)) },
            trailingIcon = { Icon(painterResource(IrohacoIcons.ExpandMore), null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    trailingIcon = if (option == value) ({ Icon(painterResource(IrohacoIcons.Check), null) }) else null,
                    onClick = { open = false; onPick(option) },
                )
            }
        }
    }
}

@Composable
internal fun kindLabel(kind: MediaKind?) = stringResource(
    when (kind) {
        MediaKind.IMAGE -> R.string.search_photos
        MediaKind.VIDEO -> R.string.search_videos
        null -> R.string.search_all_kinds
    },
)

@Composable
private fun ratingLabel(min: Int?) = min?.let { stringResource(R.string.timeline_filter_rating_at_least, "★".repeat(it)) } ?: stringResource(R.string.timeline_filter_rating)

@IrohacoPreviews
@Composable
private fun FilterRowPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface { FilterRow(TimelineQuery(favoritesOnly = true, minRating = 3, tagIds = setOf("t")), listOf(Tag("t", "海", 3)), {}) }
    }
}
