package dev.irohaco.core.contracts

/** Design-stage pure Kotlin ports; intentionally Android/Room independent. */
@JvmInline value class AssetId(val value: String)
@JvmInline value class DiscoveryId(val value: String)
@JvmInline value class ConflictId(val value: String)
@JvmInline value class BackupId(val value: String)
@JvmInline value class RestoreId(val value: String)

enum class IdentityAction { LINK_EXISTING, NEW_ASSET, IGNORE }
enum class IdentityDecisionOrigin { USER, HIGH_CONFIDENCE_POLICY, IMPORT }
enum class MetadataResolution { KEEP_LOCAL, ACCEPT_REMOTE, MANUAL_MERGE }
enum class AccessPurpose { GALLERY_VIEW, AI_INDEX, SEARCH, RAG, REMOTE_SEND, EXIF_WRITE }
enum class AccessDecision { ALLOW, DENY, RECHECK_REQUIRED }

data class MetadataTagKey(val assetId: AssetId, val namespace: String, val tagName: String)
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
sealed interface TypedMetadataValue {
    data class Text(val value: String): TypedMetadataValue
    data class WholeNumber(val value: Long): TypedMetadataValue
    data class Decimal(val value: Double): TypedMetadataValue
    data class Raw(val value: ByteArray): TypedMetadataValue
}
data class MetadataEdit(val key: MetadataTagKey, val values: List<TypedMetadataValue>?) // null means REMOVE; empty list invalid

data class IdentityEvidence(val signalCode: String, val digest: String?)
data class IdentityCandidateView(val assetId: AssetId, val confidence: Double, val signals: List<IdentityEvidence>)
data class DiscoveryView(val discoveryId: DiscoveryId, val candidateAssets: List<IdentityCandidateView>)
data class IdentityDecision(
    val discoveryId: DiscoveryId,
    val action: IdentityAction,
    val selectedAssetId: AssetId?,
    val origin: IdentityDecisionOrigin,
)

data class BackupSelection(
    val includeTags: Boolean = true,
    val includeRatings: Boolean = true,
    val includeAlbums: Boolean = true,
    val includeSemanticNotes: Boolean = true,
    val includeMetadataOverrides: Boolean = true,
    val includeEmbeddings: Boolean = false,
)
data class RestorePreview(
    val restoreId: RestoreId,
    val exactMatches: Int,
    val ambiguousMatches: Int,
    val unmatchedAssets: Int,
    val conflicts: Int,
)

data class LiveMediaAccess(val canRead: Boolean, val canWrite: Boolean, val gpsAllowed: Boolean)
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
