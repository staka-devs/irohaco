package dev.irohaco.feature.timeline

import dev.irohaco.core.contracts.TimelineItem

/**
 * Drag selection (DESIGN §4.1): everything between the long-pressed cell [from] and the cell under the
 * finger [to] is added to what was selected before the drag. Date headers and unloaded cells are skipped.
 */
internal fun rangeSelection(
    before: Map<String, TimelineItem>,
    cellAt: (Int) -> TimelineCell?,
    from: Int,
    to: Int,
): Map<String, TimelineItem> {
    val range = if (from <= to) from..to else to..from
    val added = range.mapNotNull { (cellAt(it) as? TimelineCell.Media)?.item }
    return LinkedHashMap(before).apply { added.forEach { put(it.assetId, it) } }
}

/** Adds or removes one item, keeping the order in which items were selected. */
internal fun Map<String, TimelineItem>.toggled(item: TimelineItem): Map<String, TimelineItem> =
    LinkedHashMap(this).apply { if (remove(item.assetId) == null) put(item.assetId, item) }

/** Selects the whole group, or unselects it when every item of it is already selected (GAL-012). */
internal fun Map<String, TimelineItem>.toggledGroup(group: List<TimelineItem>): Map<String, TimelineItem> =
    if (group.isNotEmpty() && group.all { it.assetId in this }) {
        LinkedHashMap(this).apply { group.forEach { remove(it.assetId) } }
    } else {
        LinkedHashMap(this).apply { group.forEach { putIfAbsent(it.assetId, it) } }
    }
