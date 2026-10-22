package dev.irohaco.core.application

import dev.irohaco.core.contracts.EmbeddingSpace
import dev.irohaco.core.contracts.MediaItems
import dev.irohaco.core.contracts.ModelLoadException
import dev.irohaco.core.contracts.ModelNotInstalledException
import dev.irohaco.core.contracts.PlaceMatch
import dev.irohaco.core.contracts.SpaceState
import dev.irohaco.core.contracts.TextMatch
import dev.irohaco.core.contracts.TimelineItem
import dev.irohaco.core.domain.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Why a result matched (SRCH-004); shown under it. */
enum class Evidence { MEANING, TEXT, MEMO, PLACE, PLACE_INFERRED }

/**
 * One photo, or one scene of a video at [timestampMs] (SRCH-002), with what matched. [ranks] holds
 * where each searched space placed it (space id to rank from 1), for comparing models (SRCH-005).
 */
data class SearchHit(
    val item: TimelineItem,
    val timestampMs: Long?,
    val evidence: Set<Evidence>,
    val text: String?,
    val ranks: Map<String, Int> = emptyMap(),
)

/** A space on the model switch. */
data class SpaceChoice(val space: EmbeddingSpace, val coverage: Float?)

/**
 * What a search found. [searched] lists the spaces that answered by meaning, each with how much of the
 * library it covers (SRCH-006); [meaningUnavailable] is true when no index or model could answer.
 */
data class SearchResults(val hits: List<SearchHit>, val searched: List<SpaceChoice>, val meaningUnavailable: Boolean)

/**
 * The search screen's one entry (SRCH-001, SRCH-002, SRCH-004〜006): by meaning in one space or, when
 * comparing, in every space, and by the text in photos and frames. The rankings are fused by rank
 * (RRF), never by adding scores, which mean nothing across spaces (ARCHITECTURE §7.2).
 */
class SearchMedia(
    private val meaning: SearchByMeaning,
    private val text: SearchText,
    private val items: MediaItems,
    /** Files at a place the query names (SRCH-003); null leaves place names out. */
    private val places: SearchPlaces? = null,
) {
    /** The spaces to pick from; one still being built carries how much it covers (AC-005). */
    fun spaces(): Flow<List<SpaceChoice>> = meaning.spaces().map { spaces ->
        spaces.map { SpaceChoice(it, if (it.state in BUILDING) meaning.coverage(it.spaceId) else null) }
    }

    suspend fun setDefault(spaceId: String) = meaning.setDefault(spaceId)

    suspend operator fun invoke(
        query: String,
        spaceId: String? = null,
        byMeaning: Boolean = true,
        byText: Boolean = true,
        kind: MediaKind? = null,
        compare: Boolean = false,
        /** Search memos, by their text and their meaning (ANN-002). */
        byMemo: Boolean = true,
        filters: SearchFilters = SearchFilters(),
        /** Places guessed from photos taken near in time count too (`include_inferred`); else only GPS (`confirmed_only`). */
        includeInferredPlaces: Boolean = true,
    ): SearchResults {
        val meant = if (byMeaning) meaningOrEmpty { if (compare) meaning.compare(query) else listOfNotNull(meaning(query, spaceId)) } else emptyList()
        val texts = if (byText || byMemo) text(query).filter { if (it.fromMemo) byMemo else byText } else emptyList()
        val atPlaces = places?.invoke(query, includeInferredPlaces).orEmpty()
        return fuse(meant.map { it.copy(hits = it.hits.filter { hit -> byMemo || !hit.fromMemo }) }, texts, kind, filters, meaningAsked = byMeaning, atPlaces)
    }

    /** Photos and video scenes like [assetId] (SRCH-001), under the same conditions as a search. */
    suspend fun similar(assetId: String, spaceId: String? = null, kind: MediaKind? = null, filters: SearchFilters = SearchFilters()): SearchResults {
        val meant = meaningOrEmpty { listOfNotNull(meaning.similar(assetId, spaceId)) }
        return fuse(meant, emptyList(), kind, filters, meaningAsked = true)
    }

    private suspend fun fuse(
        meant: List<MeaningResult>,
        texts: List<TextMatch>,
        kind: MediaKind?,
        filters: SearchFilters,
        meaningAsked: Boolean,
        atPlaces: List<PlaceMatch> = emptyList(),
    ): SearchResults {
        val fused = LinkedHashMap<Pair<String, Long?>, Fused>()
        meant.forEach { result ->
            result.hits.forEachIndexed { rank, hit ->
                val evidence = if (hit.fromMemo) Evidence.MEMO else Evidence.MEANING
                fused.getOrPut(hit.assetId to hit.timestampMs) { Fused() }.add(rank, evidence, null, result.space.spaceId)
            }
        }
        atPlaces.forEachIndexed { rank, hit ->
            fused.getOrPut(hit.assetId to null) { Fused() }.add(rank, if (hit.inferred) Evidence.PLACE_INFERRED else Evidence.PLACE, hit.placeName, null)
        }
        texts.forEachIndexed { rank, hit ->
            fused.getOrPut(hit.assetId to hit.timestampMs) { Fused() }.add(rank, if (hit.fromMemo) Evidence.MEMO else Evidence.TEXT, hit.text, null)
        }
        val found = items.items(fused.keys.map { it.first }, filters.tagId).associateBy { it.assetId }
        val hits = fused.entries
            .sortedByDescending { it.value.score }
            .mapNotNull { (key, f) ->
                val item = found[key.first]?.takeIf { (kind == null || it.kind == kind) && filters.admits(it.sortTimeMs) } ?: return@mapNotNull null
                SearchHit(item, key.second, f.evidence, f.text, f.ranks)
            }
            .take(LIMIT)
        return SearchResults(hits, meant.map { SpaceChoice(it.space, it.coverage) }, meaningAsked && meant.isEmpty())
    }

    private suspend fun meaningOrEmpty(search: suspend () -> List<MeaningResult>): List<MeaningResult> = try {
        search()
    } catch (e: ModelNotInstalledException) {
        emptyList()
    } catch (e: ModelLoadException) {
        emptyList() // the gallery and the text search keep working without the model (IST-010)
    }

    private class Fused {
        var score = 0.0
        val evidence = mutableSetOf<Evidence>()
        var text: String? = null
        val ranks = mutableMapOf<String, Int>()

        fun add(rank: Int, kind: Evidence, matchedText: String?, spaceId: String?) {
            score += 1.0 / (RRF_K + rank + 1)
            evidence += kind
            if (matchedText != null) text = matchedText
            // A photo and its memo can both match in one space; the better rank stands for both.
            if (spaceId != null) ranks.merge(spaceId, rank + 1, ::minOf)
        }
    }

    private companion object {
        /** The usual RRF constant: lower ranks still count, and no single list dominates. */
        const val RRF_K = 60
        const val LIMIT = 200
        val BUILDING = setOf(SpaceState.BUILDING, SpaceState.VERIFYING)
    }
}

/**
 * Conditions besides the query (SRCH-004): the capture time in [fromMs, untilMs) and a tag. A file
 * matches them by its capture time, or its modified time when it has none, as the timeline sorts it.
 */
data class SearchFilters(val fromMs: Long? = null, val untilMs: Long? = null, val tagId: String? = null) {
    fun admits(timeMs: Long) = (fromMs == null || timeMs >= fromMs) && (untilMs == null || timeMs < untilMs)
}
