package dev.irohaco.core.application

import dev.irohaco.core.contracts.EgressDecision
import dev.irohaco.core.contracts.EgressGrant
import dev.irohaco.core.contracts.EgressGrantStore
import dev.irohaco.core.contracts.EgressPayload
import dev.irohaco.core.contracts.EgressPolicy
import dev.irohaco.core.contracts.RemoteConnection
import dev.irohaco.core.contracts.TaskType
import kotlinx.coroutines.flow.Flow

/**
 * Sending is allowed only when every payload has its own grant for the task and no asset is kept back
 * (ADR-0002, API-007). It reads the store on each request, so a grant taken back stops the next one.
 */
class GrantedEgressPolicy(private val store: EgressGrantStore) : EgressPolicy {
    override suspend fun authorize(task: TaskType, profileId: String, payloads: Set<EgressPayload>, assetIds: Set<String>): EgressDecision {
        require(payloads.isNotEmpty()) { "A request always sends something; name what" }
        val ungranted = payloads -store.granted(profileId, task)
        val blocked = if (assetIds.isEmpty()) emptySet() else store.blocked(assetIds)
        return if (ungranted.isEmpty() && blocked.isEmpty()) EgressDecision.Allowed else EgressDecision.Denied(ungranted, blocked)
    }
}

/** The settings' list of remote connections and what each may get; everything starts off. */
class ManageEgress(private val store: EgressGrantStore) {
    fun connections(): Flow<List<RemoteConnection>> = store.connections

    fun grants(): Flow<Set<EgressGrant>> = store.grants

    suspend fun set(grant: EgressGrant, allowed: Boolean) = store.set(grant, allowed)

    companion object {
        /** What each task in R0 could send; location never leaves with an image (ARCHITECTURE §9.2). */
        val offered: Map<TaskType, List<EgressPayload>> = mapOf(
            TaskType.SEARCH_QUERY to listOf(EgressPayload.QUERY_TEXT),
            TaskType.IMAGE_INDEX to listOf(EgressPayload.IMAGE_PIXELS, EgressPayload.OCR_TEXT, EgressPayload.USER_ANNOTATIONS),
            TaskType.VIDEO_INDEX to listOf(EgressPayload.VIDEO_FRAMES),
            TaskType.RAG to listOf(
                EgressPayload.QUERY_TEXT,
                EgressPayload.IMAGE_PIXELS,
                EgressPayload.VIDEO_FRAMES,
                EgressPayload.OCR_TEXT,
                EgressPayload.USER_ANNOTATIONS,
            ),
        )
    }
}
