package dev.irohaco.core.application

import dev.irohaco.core.contracts.FolderRef
import dev.irohaco.core.contracts.IdentityCandidate
import dev.irohaco.core.contracts.IdentityStore
import dev.irohaco.core.contracts.LibraryFolder
import dev.irohaco.core.contracts.LibraryReader
import dev.irohaco.core.contracts.LibrarySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The library tab: folders per volume and the folders hidden from the timeline (PRD GAL-002). */
class BrowseLibrary(private val reader: LibraryReader, private val settings: LibrarySettings) {
    /** Re-read whenever the catalog changes, so an unplugged card shows as offline at once. */
    fun folders(): Flow<List<LibraryFolder>> = reader.changes().map { reader.folders() }

    fun excludedFolders(): Flow<Set<FolderRef>> = settings.excludedFolders

    suspend fun setExcluded(folder: FolderRef, excluded: Boolean) = settings.setExcluded(folder, excluded)
}

/**
 * DiscoverNewSources / DecideIdentity / RecheckMissingAssets (UC-ID-01, ADR-0008). Candidates are
 * only offered; the user decides, and nothing is merged on a match alone (INT-003).
 */
class ReconcileIdentity(
    private val store: IdentityStore,
    /** The catalog sync, which sees missing files again when they are back. */
    private val sync: suspend () -> Unit,
    private val now: () -> Long,
) {
    fun pending(): Flow<List<IdentityCandidate>> = store.pending()

    /** After each sync: offer new files that look like missing ones. */
    suspend fun discover(): Int = store.findCandidates()

    suspend fun sameFile(candidate: IdentityCandidate) = store.link(candidate.discoveryId, candidate.missingAssetId, now())

    suspend fun differentFile(candidate: IdentityCandidate) = store.reject(candidate.discoveryId, candidate.missingAssetId)

    /** RecheckMissingAssets: a file that is back returns to its asset; the rest are matched again. */
    suspend fun recheckMissing() {
        sync()
        discover()
    }
}
