package dev.irohaco.feature.timeline.library

import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.TimelineQuery
import dev.irohaco.core.domain.GeoBounds

/**
 * A list a grid and its viewer show: one folder, album or tag, or the favorites. The timeline's
 * order and kind still apply; folders hidden from the timeline are not hidden here.
 */
sealed interface MediaList {
    data class Folder(val folder: FolderRef) : MediaList

    data class Album(val albumId: String) : MediaList

    data class Tag(val tagId: String) : MediaList

    data object Favorites : MediaList

    /** A group on the map: the items whose file puts them in [bounds] (GAL-017). */
    data class Area(val bounds: GeoBounds) : MediaList

    fun query(timeline: TimelineQuery): TimelineQuery {
        val base = timeline.copy(excludedFolders = emptySet())
        return when (this) {
            is Folder -> base.copy(folder = folder)
            is Album -> base.copy(albumId = albumId)
            is Tag -> base.copy(tagId = tagId)
            Favorites -> base.copy(favoritesOnly = true)
            is Area -> base.copy(area = bounds)
        }
    }
}
