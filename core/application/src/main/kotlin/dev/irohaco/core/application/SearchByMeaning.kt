package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.VectorHit
import dev.irohaco.core.contracts.VectorSearchStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** What a search by meaning found, in which space, and how much of the library that space covers (AC-005). */
data class MeaningResult(val space: EmbeddingSpace, val hits: List<VectorHit>, val coverage: Float)

/**
 * Searches by meaning in one embedding space (IDX-006, IDX-007, ADR-0003): the default one, or the one
 * the user picked. A space still being built can be searched, with its coverage shown; vectors of
 * different spaces are never mixed.
 */
class SearchByMeaning(
    private val store: VectorSearchStore,
    /** Turns the query into a vector with the local model, at search priority. */
    private val embedText: suspend (String) -> FloatArray,
    private val localSignature: String = ImageSpaces.signature(ImageSpaces.LOCAL),
) {
    fun spaces(): Flow<List<EmbeddingSpace>> = store.spaces()

    /** Switches new searches to [spaceId]; only a READY space can be the default. */
    suspend fun setDefault(spaceId: String): Boolean = store.setDefault(spaceId)

    /** Null when there is nothing to search: no space yet, or one the local model cannot query. */
    suspend operator fun invoke(query: String, spaceId: String? = null): MeaningResult? {
        if (query.isBlank()) return null
        val space = (if (spaceId != null) store.space(spaceId) else store.spaces().first().firstOrNull { it.isDefault }) ?: return null
        // A query vector of another model file would land in a different space (AC-004).
        if (space.signature != localSignature || space.state !in SEARCHABLE) return null
        val query = embedText((space.queryPrefix ?: "") + query.trim())
        return MeaningResult(space, store.nearest(space.spaceId, query, LIMIT), store.coverage(space.spaceId))
    }

    private companion object {
        val SEARCHABLE = setOf(SpaceState.BUILDING, SpaceState.VERIFYING, SpaceState.READY)
        const val LIMIT = 200
    }
}
