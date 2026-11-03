package dev.irohaco.core.contracts

import kotlinx.coroutines.flow.Flow

/** What a model is used for; the codes `egress_grants` and `task_model_assignments` store (ARCHITECTURE §9.2). */
enum class TaskType { SEARCH_QUERY, IMAGE_INDEX, VIDEO_INDEX, CAPTION, RAG, OCR_ASSIST }

/** A kind of content that could leave the device (ARCHITECTURE §9.2). */
enum class EgressPayload { QUERY_TEXT, IMAGE_PIXELS, VIDEO_FRAMES, VIDEO_BYTES, OCR_TEXT, USER_ANNOTATIONS, GPS_EXIF }

/** One permission: this connection may get this payload for this task. No row means no (ADR-0002). */
data class EgressGrant(val connectionId: String, val task: TaskType, val payload: EgressPayload)

/** A connection to a model outside the device; the local runtime is not one. */
data class RemoteConnection(val id: String, val protocol: String, val baseUrl: String?)

sealed interface EgressDecision {
    data object Allowed : EgressDecision

    /** Nothing is sent, and nothing else is tried in its place (AC-009). */
    data class Denied(val ungranted: Set<EgressPayload>, val blockedAssets: Set<String>) : EgressDecision
}

/** Asked before every request to a remote model; a screen's toggle alone never allows one (ARCHITECTURE §9.3). */
interface EgressPolicy {
    suspend fun authorize(task: TaskType, profileId: String, payloads: Set<EgressPayload>, assetIds: Set<String>): EgressDecision
}

interface EgressGrantStore {
    val connections: Flow<List<RemoteConnection>>

    val grants: Flow<Set<EgressGrant>>

    /** What [profileId]'s connection may get for [task]; empty for a profile with no remote connection. */
    suspend fun granted(profileId: String, task: TaskType): Set<EgressPayload>

    /** Those of [assetIds] the user kept from being sent anywhere (`asset_privacy_rules`). */
    suspend fun blocked(assetIds: Set<String>): Set<String>

    suspend fun set(grant: EgressGrant, allowed: Boolean)
}
