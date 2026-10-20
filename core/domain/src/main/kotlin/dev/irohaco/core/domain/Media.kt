package dev.irohaco.core.domain

enum class MediaKind { IMAGE, VIDEO }

/** media_sources.access_state (ARCHITECTURE §2.1). MISSING_CANDIDATE never means the file is known to be deleted. */
enum class AssetAvailability { AVAILABLE, OFFLINE, INACCESSIBLE, MISSING_CANDIDATE }

/** How much of one media kind the user lets the app read right now (READ_MEDIA_* and the partial photo picker grant). */
enum class LibraryAccess { NONE, SELECTED, FULL }

data class MediaLibraryAccess(val images: LibraryAccess, val videos: LibraryAccess) {
    fun of(kind: MediaKind): LibraryAccess = if (kind == MediaKind.IMAGE) images else videos
}
