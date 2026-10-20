package dev.irohaco.feature.timeline.library

import dev.irohaco.core.contracts.LibraryFolder

/** One line of the folder tree: a folder with media, or a parent that only holds folders. */
internal data class FolderLine(val name: String, val depth: Int, val path: String, val folder: LibraryFolder?)

/**
 * Turns one volume's folders, sorted by path, into tree lines (DESIGN §4.4). Parents without media
 * of their own, such as "DCIM/" above "DCIM/Camera/", get a line so the hierarchy reads.
 */
internal fun folderTree(folders: List<LibraryFolder>): List<FolderLine> {
    val byPath = folders.associateBy { it.folder.relativePath }
    val paths = sortedSetOf<String>()
    for (path in byPath.keys) {
        paths += path
        val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        for (i in 1 until parts.size) paths += parts.take(i).joinToString("/", postfix = "/")
    }
    return paths.map { path ->
        val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        FolderLine(name = parts.lastOrNull().orEmpty(), depth = parts.size, path = path, folder = byPath[path])
    }
}
