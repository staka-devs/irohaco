package dev.irohaco.feature.timeline.library

import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.TimelineQuery

/**
 * A list a grid and its viewer show: one folder, album or tag, or the favorites. The timeline's
 * order and kind still apply; folders hidden from the timeline are not hidden here.
 */
sealed interface MediaList {
    data class Folder(val folder: FolderRef) : MediaList

    data class Album(val albumId: String) : MediaList

    data class Tag(val tagId: String) : MediaList

    data object Favorites : MediaList

    fun query(timeline: TimelineQuery): TimelineQuery {
        val base = timeline.copy(excludedFolders = emptySet())
        return when (this) {
            is Folder -> base.copy(folder = folder)
            is Album -> base.copy(albumId = albumId)
            is Tag -> base.copy(tagId = tagId)
            Favorites -> base.copy(favoritesOnly = true)
        }
    }
}
