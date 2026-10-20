package dev.irohaco.feature.timeline.library

import dev.irohaco.core.contracts.LibraryFolder

/**
 * One line of the folder tree: a folder with media, or a parent that only holds folders.
 * [totalCount] and [coverUri] include the subfolders, so a parent without media of its own still
 * shows how much it holds.
 */
internal data class FolderLine(
    val name: String,
    val depth: Int,
    val path: String,
    val folder: LibraryFolder?,
    val hasChildren: Boolean = false,
    val totalCount: Int = folder?.itemCount ?: 0,
    val coverUri: String? = folder?.coverUri,
)

/**
 * Turns one volume's folders, sorted by path, into tree lines (DESIGN §4.4). Parents without media
 * of their own, such as "DCIM/" above "DCIM/Camera/", get a line so the hierarchy reads. The volume
 * root ("", depth 0) is a leaf: its media is listed, but it does not fold the top-level folders.
 */
internal fun folderTree(folders: List<LibraryFolder>): List<FolderLine> {
    val byPath = folders.associateBy { it.folder.relativePath }
    val paths = sortedSetOf<String>()
    for (path in byPath.keys) {
        paths += path
        val parts = segments(path)
        for (i in 1 until parts.size) paths += parts.take(i).joinToString("/", postfix = "/")
    }
    return paths.map { path ->
        val depth = segments(path).size
        val subtree = if (depth == 0) emptyList() else byPath.filterKeys { it != path && it.startsWith(path) }.values
        val own = byPath[path]
        FolderLine(
            name = segments(path).lastOrNull().orEmpty(),
            depth = depth,
            path = path,
            folder = own,
            hasChildren = depth > 0 && paths.any { it != path && it.startsWith(path) },
            totalCount = (own?.itemCount ?: 0) + subtree.sumOf { it.itemCount },
            coverUri = own?.coverUri ?: subtree.firstNotNullOfOrNull { it.coverUri },
        )
    }
}

/**
 * The folders under the volume whose parents are all expanded; [expanded] holds paths such as
 * "DCIM/". The volume root has no line of its own: the volume is the node above them.
 */
internal fun visibleLines(lines: List<FolderLine>, expanded: Set<String>): List<FolderLine> =
    lines.filter { line -> line.depth > 0 && ancestors(line.path).all { it in expanded } }

/** The direct subfolders of [path]; for the volume root (""), the top-level folders. */
internal fun childrenOf(lines: List<FolderLine>, path: String): List<FolderLine> {
    val depth = segments(path).size
    return lines.filter { it.depth == depth + 1 && it.path.startsWith(path) }
}

/** Each folder on the way down to [path], as (name, path), not counting the volume root. */
internal fun breadcrumbs(path: String): List<Pair<String, String>> {
    val parts = segments(path)
    return parts.indices.map { i -> parts[i] to parts.take(i + 1).joinToString("/", postfix = "/") }
}

private fun segments(path: String) = path.trimEnd('/').split('/').filter { it.isNotEmpty() }

private fun ancestors(path: String): List<String> = breadcrumbs(path).dropLast(1).map { it.second }
