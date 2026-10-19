package dev.irohaco.core.domain

enum class IdentityAction { LINK_EXISTING, NEW_ASSET, IGNORE }

enum class IdentityDecisionOrigin { USER, HIGH_CONFIDENCE_POLICY, IMPORT }

/** The closed set of identity_candidate_signals.signal_code in schema.sql. */
enum class IdentitySignal {
    SOURCE_CONTINUITY,
    CONTENT_SHA256,
    VISUAL_HASH,
    EXIF_TIME,
    DIMENSIONS,
    FILE_NAME,
    USER_CONFIRMATION,
}

data class IdentityEvidence(val signal: IdentitySignal, val digest: String?)

/**
 * A decision about a discovery (USE_CASES UC-ID-01). Only LINK_EXISTING names an existing asset;
 * NEW_ASSET gets its id from the use case that creates the asset, and IGNORE links nothing.
 */
data class IdentityDecision(
    val discoveryId: DiscoveryId,
    val action: IdentityAction,
    val selectedAssetId: AssetId?,
    val origin: IdentityDecisionOrigin,
) {
    init {
        require((action == IdentityAction.LINK_EXISTING) == (selectedAssetId != null)) {
            "selectedAssetId is required for LINK_EXISTING and must be null for $action"
        }
    }
}
