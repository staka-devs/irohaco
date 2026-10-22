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
    /** The SHA-256 of the model file the app runs; the catalog pins it. */
    private val modelDigest: String = ModelCatalog.EMBEDDING.sha256,
) {
    fun spaces(): Flow<List<EmbeddingSpace>> = store.spaces()

    /** Switches new searches to [spaceId]; only a READY space can be the default. */
    suspend fun setDefault(spaceId: String): Boolean = store.setDefault(spaceId)

    suspend fun coverage(spaceId: String): Float = store.coverage(spaceId)

    /**
     * Marks spaces of another model file STALE and cancels their jobs; run before indexing, so a job
     * paused before the model changed cannot mix vectors of two model files in one space.
     */
    suspend fun retireOldSpaces() {
        val old = store.spaces().first().filter { it.state !in RETIRED && it.signature != ImageSpaces.signature(it.specWith(modelDigest)) }
        store.markStale(old.map { it.spaceId })
    }

    /** Null when there is nothing to search: no space yet, or one the local model cannot query. */
    suspend operator fun invoke(query: String, spaceId: String? = null): MeaningResult? {
        if (query.isBlank()) return null
        val space = (if (spaceId != null) store.space(spaceId) else store.spaces().first().firstOrNull { it.isDefault }) ?: return null
        if (!searchable(space)) return null
        return search(space, query, mutableMapOf())
    }

    /**
     * Photos and video scenes near [assetId] in the default space or [spaceId] (SRCH-001); the photo
     * itself is left out. Null when the space has no vector of it yet.
     */
    suspend fun similar(assetId: String, spaceId: String? = null): MeaningResult? {
        val space = (if (spaceId != null) store.space(spaceId) else store.spaces().first().firstOrNull { it.isDefault }) ?: return null
        if (!searchable(space)) return null
        val vector = store.vectorOf(space.spaceId, assetId) ?: return null
        val hits = store.nearest(space.spaceId, vector, LIMIT + 1).filter { it.assetId != assetId }
        return MeaningResult(space, hits, store.coverage(space.spaceId))
    }

    /**
     * Searches every space the local model can query, each on its own (SRCH-005). The caller fuses
     * the rankings; similarities of different spaces are never compared.
     */
    suspend fun compare(query: String): List<MeaningResult> {
        if (query.isBlank()) return emptyList()
        val queries = mutableMapOf<String?, FloatArray>()
        return searchableSpaces().map { search(it, query, queries) }
    }

    /** Spaces built with the model file that runs now, and not retired. */
    suspend fun searchableSpaces(): List<EmbeddingSpace> = store.spaces().first().filter(::searchable)

    // Built with the model file that runs now: a query vector of another file would land elsewhere (AC-004).
    private fun searchable(space: EmbeddingSpace) = space.state in SEARCHABLE && space.signature == ImageSpaces.signature(space.specWith(modelDigest))

    /** [queries] keeps the query vector per prefix: spaces of one model share it. */
    private suspend fun search(space: EmbeddingSpace, query: String, queries: MutableMap<String?, FloatArray>): MeaningResult {
        val vector = queries.getOrPut(space.queryPrefix) { embedText((space.queryPrefix ?: "") + query.trim()) }
        return MeaningResult(space, store.nearest(space.spaceId, vector, LIMIT), store.coverage(space.spaceId))
    }

    private companion object {
        val SEARCHABLE = setOf(SpaceState.BUILDING, SpaceState.VERIFYING, SpaceState.READY)
        val RETIRED = setOf(SpaceState.STALE, SpaceState.DELETING)
        const val LIMIT = 200
    }
}
