package dev.irohaco.core.contracts

import dev.irohaco.core.domain.AssetAvailability
import dev.irohaco.core.domain.MediaKind
import dev.irohaco.core.domain.MediaLibraryAccess
import kotlinx.coroutines.flow.Flow

/** One MediaStore row as it is now (ARCHITECTURE §6.1). */
data class MediaStoreEntry(
    val mediaStoreId: Long,
    val kind: MediaKind,
    val contentUri: String,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long?,
    val takenAtMs: Long?,
    /** A video's length; null for photos and when MediaStore does not know it. */
    val durationMs: Long? = null,
)

/** Reads MediaStore. Every call reflects only what the app may read at that moment. */
interface MediaCatalogSource {
    /** External volume names mounted now. */
    suspend fun volumes(): Set<String>

    suspend fun version(volume: String): String

    suspend fun generation(volume: String): Long

    /** Images and videos whose generation_modified is greater than [sinceGeneration]. */
    suspend fun changedSince(volume: String, sinceGeneration: Long): List<MediaStoreEntry>

    /** Every image and video id the app can see now; the generation diff never reports removals. */
    suspend fun visibleIds(volume: String): Set<Long>

    /**
     * Entries for ids that became visible without a newer generation, e.g. photos the user added to
     * a partial selection or media that was there before access was granted.
     */
    suspend fun entries(volume: String, ids: Set<Long>): List<MediaStoreEntry>
}

/** Asks the OS each time; never cache the answer as an authorization (ADR-0009). */
fun interface MediaLibraryAccessPort {
    fun current(): MediaLibraryAccess
}

data class CatalogVolume(
    val volumeId: String,
    val providerVolumeName: String,
    val mediaStoreVersion: String?,
    val lastGeneration: Long?,
)

data class KnownSource(
    val sourceId: String,
    val mediaStoreId: Long?,
    val kind: MediaKind,
    val availability: AssetAvailability,
)

data class NewCatalogAsset(
    val discoveryId: String,
    val assetId: String,
    val sourceId: String,
    val volumeId: String,
    val entry: MediaStoreEntry,
)

data class VolumeSync(val volumeId: String, val mediaStoreVersion: String, val generation: Long)

/** Everything one catalog sync changes, applied by [CatalogStore.apply] in a single transaction. */
data class CatalogChanges(
    val observedAtMs: Long,
    val newVolumes: List<CatalogVolume> = emptyList(),
    val onlineVolumeIds: Set<String> = emptySet(),
    val offlineVolumeIds: Set<String> = emptySet(),
    /** Sources whose MediaStore id is no longer trustworthy (the volume's MediaStore version changed). */
    val detachedSourceIds: Set<String> = emptySet(),
    val newAssets: List<NewCatalogAsset> = emptyList(),
    /** Seen again under the same volume and MediaStore id: refresh the observed fields and mark AVAILABLE. */
    val refreshedSources: Map<String, MediaStoreEntry> = emptyMap(),
    val availability: Map<String, AssetAvailability> = emptyMap(),
    val syncedVolumes: List<VolumeSync> = emptyList(),
)

/**
 * A newly found file that may be a missing one seen again (ADR-0008, UC-ID-01). The new file is
 * already its own asset; linking moves it, and the user's marks, onto the missing asset.
 */
data class IdentityCandidate(
    val discoveryId: String,
    val missingAssetId: String,
    val newAssetId: String,
    val fileName: String,
    val missingFolder: String?,
    val newFolder: String?,
    val newContentUri: String,
)

interface IdentityStore {
    /**
     * Records candidates for new files whose name and capture time both match a missing asset.
     * Candidates once decided are never offered again. Returns how many were added.
     */
    suspend fun findCandidates(): Int

    /** Undecided candidates whose old asset is still missing. */
    fun pending(): Flow<List<IdentityCandidate>>

    /** The user says it is the same file (LINK_EXISTING by USER). */
    suspend fun link(discoveryId: String, missingAssetId: String, atMs: Long)

    /** The user says it is another file; the new asset stays as it is. */
    suspend fun reject(discoveryId: String, missingAssetId: String)
}

interface CatalogStore {
    suspend fun volumes(): List<CatalogVolume>

    suspend fun knownSources(volumeId: String): List<KnownSource>

    suspend fun apply(changes: CatalogChanges)
}
