package dev.irohaco.core.domain

import java.time.LocalDateTime

/** One metadata field, telling apart a missing value from one the app may not or cannot read (PRD MET-001). */
sealed interface Detail<out T> {
    data class Value<T>(val value: T) : Detail<T>

    /** The file has no such value. */
    data object Missing : Detail<Nothing>

    /** The value exists but the OS withholds it, e.g. GPS without the location permission. */
    data object NoPermission : Detail<Nothing>

    /** The value is there but could not be parsed. */
    data object Unreadable : Detail<Nothing>
}

data class GeoPoint(val latitude: Double, val longitude: Double)

/** What the details sheet shows for one photo or video; read on demand, not stored. */
data class MediaDetails(
    val fileName: String,
    val folder: Detail<String>,
    val sizeBytes: Detail<Long>,
    val mimeType: Detail<String>,
    val widthPx: Detail<Int>,
    val heightPx: Detail<Int>,
    val takenAt: Detail<LocalDateTime>,
    val durationMs: Detail<Long>,
    val camera: Detail<String>,
    val lens: Detail<String>,
    val fNumber: Detail<Double>,
    val exposureSeconds: Detail<Double>,
    val iso: Detail<Int>,
    val focalLengthMm: Detail<Double>,
    val location: Detail<GeoPoint>,
    val description: Detail<String>,
    val keywords: Detail<List<String>>,
    val headline: Detail<String> = Detail.Missing,
    val creator: Detail<String> = Detail.Missing,
    val copyright: Detail<String> = Detail.Missing,
)

sealed interface MediaDetailsResult {
    data class Read(val details: MediaDetails) : MediaDetailsResult

    /** The file itself could not be opened (moved, deleted or access withdrawn). */
    data object FileUnavailable : MediaDetailsResult
}
