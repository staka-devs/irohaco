package dev.irohaco.core.domain

/** Names the user types for a file or a new folder (GAL-005): not blank, no path separator, not "." or "..". */
fun isValidName(input: String): Boolean {
    val name = input.trim()
    return name.isNotEmpty() && '/' !in name && name != "." && name != ".."
}

/** The name without its extension; a leading dot (".nomedia") is not an extension. */
fun baseName(fileName: String): String = extensionStart(fileName)?.let { fileName.substring(0, it) } ?: fileName

/** [base] with the extension of [current], so renaming never changes the file type. */
fun renamedFile(current: String, base: String): String =
    base.trim() + (extensionStart(current)?.let { current.substring(it) } ?: "")

/** New folders go under Pictures/, which MediaStore accepts for both photos and videos. */
fun newFolderPath(name: String): String = "Pictures/${name.trim()}/"

private fun extensionStart(fileName: String): Int? = fileName.lastIndexOf('.').takeIf { it > 0 }
