package dev.irohaco.core.application

import dev.irohaco.core.contracts.InferredPlace
import dev.irohaco.core.contracts.PlaceEvidenceStore
import dev.irohaco.core.contracts.PlaceMatch
import dev.irohaco.core.contracts.PlaceNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.text.Normalizer
import kotlin.math.abs

/**
 * Gives the GPS points of the library their place names, about 1 km at a time, so that a search can
 * name a place (PRD §6). The lookup goes out, so it runs only once the user allowed it (ADR-0011).
 */
class NamePlaces(private val store: PlaceEvidenceStore, private val places: PlaceNames, private val allowed: Flow<Boolean>) {
    suspend operator fun invoke() {
        if (!allowed.first()) return
        while (true) {
            val areas = store.unnamedAreas(BATCH).ifEmpty { return }
            // shortcut: a failed lookup is kept as "no name" like the sea is; the viewer looks the file up again when shown.
            areas.forEach { store.nameArea(it, places.nameOf(it).orEmpty()) }
        }
    }

    private companion object {
        const val BATCH = 50
    }
}

/**
 * Guesses where a photo without GPS was taken from the located photo taken closest in time, within
 * [windowMs] (PRD §6, NEARBY_MEDIA). The guess is a candidate with its reason; it never goes into EXIF.
 */
class InferPlaces(private val store: PlaceEvidenceStore, private val windowMs: Long = WINDOW_MS) {
    suspend operator fun invoke() {
        val located = store.locatedTimes().sortedBy { it.timeMs }
        val times = LongArray(located.size) { located[it].timeMs }
        val guesses = store.unlocatedTimes().mapNotNull { (assetId, timeMs) ->
            val nearest = nearestIndex(times, timeMs) ?: return@mapNotNull null
            val gap = abs(times[nearest] - timeMs)
            if (gap > windowMs) return@mapNotNull null
            val from = located[nearest]
            // 0.9 for the same moment, down to 0.1 at the edge of the window.
            InferredPlace(assetId, from.point, from.placeName?.takeIf { it.isNotEmpty() }, 0.1 + 0.8 * (1 - gap.toDouble() / windowMs), from.assetId)
        }
        store.replaceInferred(guesses)
    }

    private fun nearestIndex(times: LongArray, timeMs: Long): Int? {
        if (times.isEmpty()) return null
        val at = times.binarySearch(timeMs).let { if (it >= 0) it else -it - 1 }
        return listOf(at - 1, at).filter { it in times.indices }.minBy { abs(times[it] - timeMs) }
    }

    private companion object {
        const val WINDOW_MS = 60 * 60 * 1000L
    }
}

/**
 * Finds files at a place the query names (SRCH-003, AC-006): a place matches when the query holds one
 * of its parts, such as the city, without the trailing 市, 県 and the like.
 */
class SearchPlaces(private val store: PlaceEvidenceStore) {
    suspend operator fun invoke(query: String, includeInferred: Boolean): List<PlaceMatch> {
        val names = store.placeNames(includeInferred).filter { mentions(query, it) }.ifEmpty { return emptyList() }
        return store.filesAt(names, includeInferred, LIMIT)
    }

    internal companion object {
        const val LIMIT = 200

        fun mentions(query: String, placeName: String): Boolean {
            val text = fold(query)
            return placeName.split(",").map { fold(it.trim()) }.any { part ->
                // One suffix only: 京都府 is 京都, and 京都 stays 京都.
                val core = if (part.length > 2 && part.last() in ADMIN_SUFFIXES) part.dropLast(1) else part
                core.length >= 2 && core in text
            }
        }

        private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()

        private val ADMIN_SUFFIXES = charArrayOf('都', '道', '府', '県', '市', '区', '町', '村')
    }
}
