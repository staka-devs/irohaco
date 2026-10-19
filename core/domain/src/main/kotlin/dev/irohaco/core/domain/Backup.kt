package dev.irohaco.core.domain

/** What a user-initiated export includes (USE_CASES UC-BACKUP-01). Originals and secrets are never included. */
data class BackupSelection(
    val includeTags: Boolean = true,
    val includeRatings: Boolean = true,
    val includeAlbums: Boolean = true,
    val includeSemanticNotes: Boolean = true,
    val includeMetadataOverrides: Boolean = true,
    val includeEmbeddings: Boolean = false,
)

/** Counts shown before a restore is applied (USE_CASES UC-RESTORE-01). */
data class RestorePreview(
    val restoreId: RestoreId,
    val exactMatches: Int,
    val ambiguousMatches: Int,
    val unmatchedAssets: Int,
    val conflicts: Int,
) {
    init {
        require(exactMatches >= 0 && ambiguousMatches >= 0 && unmatchedAssets >= 0 && conflicts >= 0) {
            "Restore preview counts must not be negative"
        }
    }
}
