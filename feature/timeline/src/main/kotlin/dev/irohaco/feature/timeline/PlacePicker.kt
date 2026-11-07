package dev.irohaco.feature.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.irohaco.core.designsystem.component.IrohacoBottomSheet
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing

/** A prefecture or other top place, the places under it, and how many files each holds. */
internal data class PlaceGroup(val name: String, val count: Int, val towns: List<Pair<String, Int>>)

/** Groups place names as the app stores them, "town, prefecture", by their last part; most files first. */
internal fun groupPlaces(counts: Map<String, Int>): List<PlaceGroup> =
    counts.entries.groupBy { it.key.substringAfterLast(", ") }.map { (top, entries) ->
        PlaceGroup(
            name = top,
            count = entries.sumOf { it.value },
            towns = entries.filter { it.key.contains(", ") }.map { it.key to it.value }.sortedByDescending { it.second },
        )
    }.sortedByDescending { it.count }

/**
 * The place chip: shows the place chosen, and opens a sheet of the places photos were taken at, prefectures first,
 * each opening to its towns, with the number of files. [located] adds "with a place" and "without one" where they apply.
 */
@Composable
internal fun PlaceChip(
    chosen: String?,
    counts: suspend () -> Map<String, Int>,
    onPick: (place: String?, located: Boolean?) -> Unit,
    located: Boolean? = null,
    offerLocated: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    FilterChip(
        selected = chosen != null || located != null,
        onClick = { open = true },
        label = {
            Text(
                chosen?.substringBefore(", ")
                    ?: when (located) {
                        true -> stringResource(R.string.timeline_filter_located)
                        false -> stringResource(R.string.timeline_filter_unlocated)
                        null -> stringResource(R.string.timeline_filter_place)
                    },
            )
        },
        leadingIcon = { Icon(painterResource(IrohacoIcons.LocationOn), null) },
        trailingIcon = { Icon(painterResource(IrohacoIcons.ExpandMore), null) },
    )
    if (open) {
        val places by produceState<Map<String, Int>?>(null) { value = counts() }
        IrohacoBottomSheet(onDismiss = { open = false }) {
            PlaceList(places, chosen, located, offerLocated) { place, with ->
                open = false
                onPick(place, with)
            }
        }
    }
}

@Composable
private fun PlaceList(places: Map<String, Int>?, chosen: String?, located: Boolean?, offerLocated: Boolean, onPick: (String?, Boolean?) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup().padding(bottom = IrohacoSpacing.l)) {
        Text(stringResource(R.string.timeline_filter_place), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = IrohacoSpacing.l))
        @Composable
        fun Choice(label: String, selected: Boolean, count: Int? = null, indent: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) = ListItem(
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            supportingContent = count?.let { { Text(stringResource(R.string.timeline_place_count, it)) } },
            trailingContent = trailing,
            modifier = Modifier.padding(start = if (indent) 40.dp else 0.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        ) { Text(label) }
        Choice(stringResource(R.string.timeline_place_any), chosen == null && located == null) { onPick(null, null) }
        if (offerLocated) {
            Choice(stringResource(R.string.timeline_filter_located), chosen == null && located == true) { onPick(null, true) }
            Choice(stringResource(R.string.timeline_filter_unlocated), located == false) { onPick(null, false) }
        }
        HorizontalDivider()
        if (places == null) {
            LinearProgressIndicator(Modifier.padding(IrohacoSpacing.l))
            return@Column
        }
        if (places.isEmpty()) Text(stringResource(R.string.timeline_place_none), modifier = Modifier.padding(IrohacoSpacing.l))
        groupPlaces(places).forEach { group ->
            var expanded by remember(group.name) { mutableStateOf(chosen?.endsWith(group.name) == true && chosen != group.name) }
            Choice(
                group.name,
                chosen == group.name,
                group.count,
                trailing = if (group.towns.isEmpty()) null else {
                    {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                painterResource(IrohacoIcons.ExpandMore),
                                stringResource(if (expanded) R.string.library_collapse else R.string.library_expand, group.name),
                                modifier = Modifier.rotate(if (expanded) 180f else 0f),
                            )
                        }
                    }
                },
            ) { onPick(group.name, null) }
            if (expanded) group.towns.forEach { (name, count) -> Choice(name.substringBefore(", "), chosen == name, count, indent = true) { onPick(name, null) } }
        }
    }
}

@IrohacoPreviews
@Composable
private fun PlaceListPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            PlaceList(mapOf("徳島市, 徳島県" to 120, "三好市, 徳島県" to 30, "大阪市, 大阪府" to 80, "Paris, France" to 12), "三好市, 徳島県", null, offerLocated = true) { _, _ -> }
        }
    }
}
