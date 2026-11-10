package dev.irohaco.feature.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.irohaco.core.designsystem.component.IrohacoBottomSheet
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme
import dev.irohaco.core.designsystem.tokens.IrohacoSpacing
import dev.irohaco.core.domain.GeoBounds

/** What the place chip narrows to: a place name, a part of the map, or only files with or without a place. */
data class PlaceChoice(val place: String? = null, val area: GeoBounds? = null, val located: Boolean? = null) {
    val chosen: Boolean get() = place != null || area != null || located != null
}

/**
 * Opens the map to choose a part of it, around a place or landmark the user typed, else at the part chosen before;
 * null back means the user left without one. The app provides it, as the map needs its consent and the points of the
 * library; without it there is no map.
 */
val LocalAreaPicker = staticCompositionLocalOf<(@Composable (initial: GeoBounds?, lookFor: String?, onDone: (GeoBounds?) -> Unit) -> Unit)?> { null }

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

/** The places, prefectures and towns alike, with a part that holds [text]; most files first. */
internal fun matchPlaces(counts: Map<String, Int>, text: String): List<Pair<String, Int>> {
    val word = text.trim()
    if (word.isEmpty()) return emptyList()
    val tops = groupPlaces(counts).map { it.name to it.count }
    val towns = counts.entries.filter { it.key.contains(", ") }.map { it.key to it.value }
    return (tops + towns).filter { (name, _) -> name.split(", ").any { it.contains(word, ignoreCase = true) } }.sortedByDescending { it.second }
}

/**
 * The place chip: shows what it narrows to, and opens a sheet where a place is typed, picked from the places photos
 * were taken at (prefectures first, each opening to its towns), or chosen as a part of the map.
 */
@Composable
internal fun PlaceChip(
    choice: PlaceChoice,
    counts: suspend () -> Map<String, Int>,
    onPick: (PlaceChoice) -> Unit,
    /** Offers "with a place" and "without one", where files without a place can be listed. */
    offerLocated: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var onMap by rememberSaveable { mutableStateOf(false) }
    var lookFor by rememberSaveable { mutableStateOf<String?>(null) }
    val areaPicker = LocalAreaPicker.current
    FilterChip(
        selected = choice.chosen,
        onClick = { open = true },
        label = {
            Text(
                when {
                    choice.place != null -> choice.place.substringBefore(", ")
                    choice.area != null -> stringResource(R.string.timeline_place_area)
                    choice.located == true -> stringResource(R.string.timeline_filter_located)
                    choice.located == false -> stringResource(R.string.timeline_filter_unlocated)
                    else -> stringResource(R.string.timeline_filter_place)
                },
            )
        },
        leadingIcon = { Icon(painterResource(IrohacoIcons.LocationOn), null) },
        trailingIcon = { Icon(painterResource(IrohacoIcons.ExpandMore), null) },
    )
    if (open) {
        val places by produceState<Map<String, Int>?>(null) { value = counts() }
        IrohacoBottomSheet(onDismiss = { open = false }) {
            PlacePanel(
                places, choice, offerLocated,
                onMap = if (areaPicker == null) null else ({ typed -> open = false; lookFor = typed; onMap = true }),
            ) {
                open = false
                onPick(it)
            }
        }
    }
    if (onMap && areaPicker != null) {
        Dialog(onDismissRequest = { onMap = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            areaPicker(choice.area, lookFor) { area ->
                onMap = false
                if (area != null) onPick(PlaceChoice(area = area))
            }
        }
    }
}

@Composable
private fun PlacePanel(places: Map<String, Int>?, choice: PlaceChoice, offerLocated: Boolean, onMap: ((lookFor: String?) -> Unit)?, onPick: (PlaceChoice) -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = IrohacoSpacing.l)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = IrohacoSpacing.l), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.timeline_filter_place), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (choice.chosen) TextButton(onClick = { onPick(PlaceChoice()) }) { Text(stringResource(R.string.timeline_filter_clear)) }
        }
        if (offerLocated) {
            Row(Modifier.padding(horizontal = IrohacoSpacing.l), horizontalArrangement = Arrangement.spacedBy(IrohacoSpacing.s)) {
                listOf(true to R.string.timeline_filter_located, false to R.string.timeline_filter_unlocated).forEach { (with, label) ->
                    val selected = choice.located == with
                    FilterChip(
                        selected = selected,
                        onClick = { onPick(if (selected) PlaceChoice() else PlaceChoice(located = with)) },
                        label = { Text(stringResource(label)) },
                        leadingIcon = if (selected) ({ Icon(painterResource(IrohacoIcons.Check), null) }) else null,
                    )
                }
            }
        }
        OutlinedTextField(
            typed,
            { typed = it },
            placeholder = { Text(stringResource(R.string.timeline_place_search)) },
            leadingIcon = { Icon(painterResource(IrohacoIcons.Search), null) },
            trailingIcon = if (typed.isEmpty()) null else ({ IconButton(onClick = { typed = "" }) { Icon(painterResource(IrohacoIcons.Close), stringResource(R.string.timeline_place_search_clear)) } }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = IrohacoSpacing.l, vertical = IrohacoSpacing.s),
        )
        if (onMap != null) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.timeline_place_on_map)) },
                leadingContent = { Icon(painterResource(IrohacoIcons.Map), null) },
                trailingContent = if (choice.area != null) ({ Icon(painterResource(IrohacoIcons.Check), stringResource(R.string.timeline_place_chosen)) }) else null,
                modifier = Modifier.clickable { onMap(null) },
            )
        }
        HorizontalDivider()
        when {
            places == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(IrohacoSpacing.l))
            places.isEmpty() -> Text(stringResource(R.string.timeline_place_none), modifier = Modifier.padding(IrohacoSpacing.l))
            typed.isNotBlank() -> {
                val found = remember(places, typed) { matchPlaces(places, typed) }
                if (found.isEmpty()) Text(stringResource(R.string.timeline_place_no_match, typed.trim()), modifier = Modifier.padding(IrohacoSpacing.l))
                // A landmark or any place photos are not named by: the map opens around it.
                if (onMap != null) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.timeline_place_around_on_map, typed.trim())) },
                        leadingContent = { Icon(painterResource(IrohacoIcons.Map), null) },
                        modifier = Modifier.clickable { onMap(typed.trim()) },
                    )
                }
                found.forEach { (name, count) ->
                    PlaceRow(name.substringBefore(", "), name.substringAfter(", ", ""), count, choice.place == name) { onPick(PlaceChoice(place = name)) }
                }
            }
            else -> groupPlaces(places).forEach { group ->
                var expanded by remember(group.name) { mutableStateOf(choice.place?.endsWith(", " + group.name) == true) }
                PlaceRow(
                    group.name, "", group.count, choice.place == group.name,
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
                ) { onPick(PlaceChoice(place = group.name)) }
                if (expanded) {
                    group.towns.forEach { (name, count) ->
                        PlaceRow(name.substringBefore(", "), "", count, choice.place == name, indent = true) { onPick(PlaceChoice(place = name)) }
                    }
                }
            }
        }
    }
}

/** One place: its name, what it is in, and its number of files; a check when it is the one chosen. */
@Composable
private fun PlaceRow(name: String, within: String, count: Int, chosen: Boolean, indent: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val number = stringResource(R.string.timeline_place_count, count)
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(if (within.isEmpty()) number else "$within · $number") },
        leadingContent = {
            if (chosen) Icon(painterResource(IrohacoIcons.Check), stringResource(R.string.timeline_place_chosen), tint = MaterialTheme.colorScheme.primary)
            else Icon(painterResource(IrohacoIcons.LocationOn), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = trailing,
        modifier = Modifier.padding(start = if (indent) 40.dp else 0.dp).clickable(onClick = onClick),
    )
}

@IrohacoPreviews
@Composable
private fun PlacePanelPreview() {
    IrohacoTheme(dynamicColor = false) {
        Surface {
            PlacePanel(
                mapOf("徳島市, 徳島県" to 120, "三好市, 徳島県" to 30, "大阪市, 大阪府" to 80, "Paris, France" to 12),
                PlaceChoice(place = "三好市, 徳島県"),
                offerLocated = true,
                onMap = {},
            ) {}
        }
    }
}
