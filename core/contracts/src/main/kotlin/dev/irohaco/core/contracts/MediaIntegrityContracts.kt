package dev.irohaco.core.contracts

import dev.irohaco.core.domain.AccessDecision
import dev.irohaco.core.domain.AccessPurpose
import dev.irohaco.core.domain.AssetId
import dev.irohaco.core.domain.BackupId
import dev.irohaco.core.domain.BackupSelection
import dev.irohaco.core.domain.ConflictId
import dev.irohaco.core.domain.DiscoveryId
import dev.irohaco.core.domain.IdentityDecision
import dev.irohaco.core.domain.IdentityEvidence
import dev.irohaco.core.domain.LiveMediaAccess
import dev.irohaco.core.domain.MetadataEdit
import dev.irohaco.core.domain.MetadataResolution
import dev.irohaco.core.domain.MetadataTagKey
import dev.irohaco.core.domain.RestoreId
import dev.irohaco.core.domain.RestorePreview
import dev.irohaco.core.domain.TypedMetadataValue

/** Base, local and remote values of one tag for 3-way conflict resolution (USE_CASES UC-META-02). */
data class MetadataConflictView(
    val conflictId: ConflictId,
    val key: MetadataTagKey,
    val baseRevisionId: String,
    val localRevisionNo: Long,
    val remoteRevisionId: String,
    val baseValues: List<TypedMetadataValue>,
    val localValues: List<TypedMetadataValue>,
    val remoteValues: List<TypedMetadataValue>,
)

data class IdentityCandidateView(val assetId: AssetId, val confidence: Double, val signals: List<IdentityEvidence>) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be between 0 and 1" }
    }
}

data class DiscoveryView(val discoveryId: DiscoveryId, val candidateAssets: List<IdentityCandidateView>)

interface LiveMediaPermissionPort {
    /** Consult Android OS at call time; never use a stored observation as an authorization. */
    suspend fun checkNow(assetId: AssetId, purpose: AccessPurpose): LiveMediaAccess
}

interface MetadataIntegrityUseCases {
    /** Commit only if expected local revision and base/current original revision still match; otherwise return a conflict. */
    suspend fun editMetadata(edit: MetadataEdit, expectedLocalRevision: Long): ConflictId?

    suspend fun discoverConflicts(assetId: AssetId): List<MetadataConflictView>

    suspend fun resolveConflict(conflictId: ConflictId, resolution: MetadataResolution, manualValues: List<TypedMetadataValue>? = null)

    /** Must re-check real file digest/revision, OS write access, journal + post-write verification. */
    suspend fun exportMetadata(assetId: AssetId)
}

interface MediaIdentityUseCases {
    /** Discoveries are independent of MediaAsset until adjudicated; hashes alone must not merge copies. */
    suspend fun discoverNewSources(): List<DiscoveryView>

    suspend fun decideIdentity(decision: IdentityDecision): AssetId?

    suspend fun recheckMissingAssets(): Int
}

interface PermissionIntegrityUseCases {
    suspend fun evaluate(assetId: AssetId, purpose: AccessPurpose): AccessDecision

    suspend fun reconcileAfterResume()

    suspend fun revokeDerivedAccess(assetId: AssetId)
}

interface PortableBackupUseCases {
    /** User-initiated: excludes originals, credentials, grants, permission state by default. */
    suspend fun exportEncrypted(selection: BackupSelection, passphrase: CharArray): BackupId

    suspend fun previewEncryptedImport(archiveUri: String, passphrase: CharArray): RestorePreview

    /** Only apply after preview + explicit user confirmation. */
    suspend fun applyRestore(restoreId: RestoreId)

    suspend fun verifyRestore(restoreId: RestoreId): Boolean
}
