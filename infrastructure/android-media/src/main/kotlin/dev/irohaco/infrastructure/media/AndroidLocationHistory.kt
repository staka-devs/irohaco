package dev.irohaco.infrastructure.media

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import dev.irohaco.core.contracts.HistoryEntry
import dev.irohaco.core.contracts.LocationHistoryReader
import dev.irohaco.core.contracts.UnreadableHistoryException
import dev.irohaco.core.contracts.UnreadableHistoryException.Reason
import dev.irohaco.core.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Reader
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Reads a `Timeline.json` the user picked, on the device only (ADR-0029). The format is not documented, so
 * this follows the files Google writes: the device export (`semanticSegments`, or a bare array of segments)
 * and the older Takeout files (`timelineObjects`, `locations`). The file is read one entry at a time, so a
 * history of many years never sits in memory whole.
 */
class AndroidLocationHistory(private val context: Context) : LocationHistoryReader {
    override suspend fun read(contentUri: String, keep: (startMs: Long, endMs: Long) -> Boolean): List<HistoryEntry> =
        withContext(Dispatchers.IO) {
            val input = try {
                context.contentResolver.openInputStream(Uri.parse(contentUri))
            } catch (e: IOException) {
                null
            } ?: throw UnreadableHistoryException(Reason.NOT_JSON)
            input.bufferedReader().use { readHistory(it, keep) }
        }
}

/** The entries of [source] that [keep] wants; throws [UnreadableHistoryException] when it is not a known history. */
internal fun readHistory(source: Reader, keep: (startMs: Long, endMs: Long) -> Boolean): List<HistoryEntry> {
    val kept = mutableListOf<HistoryEntry>()
    var seen = 0
    fun add(entries: List<HistoryEntry>) {
        seen += entries.size
        entries.filterTo(kept) {
            when (it) {
                is HistoryEntry.Visit -> keep(it.startMs, it.endMs)
                is HistoryEntry.Point -> keep(it.timeMs, it.timeMs)
            }
        }
    }
    var known = false
    try {
        val json = JsonReader(source)
        when (json.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                known = true
                json.eachElement { add(segment(it)) }
            }
            JsonToken.BEGIN_OBJECT -> {
                json.beginObject()
                while (json.hasNext()) {
                    val reading: ((Map<*, *>) -> List<HistoryEntry>)? = when (json.nextName()) {
                        "semanticSegments" -> ::segment
                        "rawSignals" -> ::rawSignal
                        "timelineObjects" -> ::timelineObject
                        "locations" -> ::record
                        else -> null
                    }
                    if (reading == null) {
                        json.skipValue()
                    } else {
                        known = true
                        json.eachElement { add(reading(it)) }
                    }
                }
                json.endObject()
            }
            else -> throw UnreadableHistoryException(Reason.UNKNOWN_FORMAT)
        }
    } catch (e: IOException) {
        throw UnreadableHistoryException(Reason.NOT_JSON)
    } catch (e: IllegalStateException) {
        throw UnreadableHistoryException(Reason.NOT_JSON)
    }
    if (!known) throw UnreadableHistoryException(Reason.UNKNOWN_FORMAT)
    if (seen == 0) throw UnreadableHistoryException(Reason.EMPTY)
    return kept
}

/** Reads each object of the array at hand as a small tree; anything else in it is skipped. */
private fun JsonReader.eachElement(block: (Map<*, *>) -> Unit) {
    beginArray()
    while (hasNext()) (readValue() as? Map<*, *>)?.let(block)
    endArray()
}

private fun JsonReader.readValue(): Any? = when (peek()) {
    JsonToken.BEGIN_OBJECT -> {
        beginObject()
        val map = LinkedHashMap<String, Any?>()
        while (hasNext()) map[nextName()] = readValue()
        endObject()
        map
    }
    JsonToken.BEGIN_ARRAY -> {
        beginArray()
        val list = ArrayList<Any?>()
        while (hasNext()) list += readValue()
        endArray()
        list
    }
    JsonToken.NUMBER -> nextDouble()
    JsonToken.BOOLEAN -> nextBoolean()
    JsonToken.NULL -> nextNull().let { null }
    else -> nextString()
}

private fun Map<*, *>.obj(key: String) = this[key] as? Map<*, *>

private fun Map<*, *>.list(key: String) = (this[key] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

private fun Map<*, *>.text(key: String) = this[key] as? String

/** "35.0116°, 135.7681°" from the device export, or "geo:35.0116,135.7681" from its array form. */
private val LAT_LNG = Regex("""(-?\d+(?:\.\d+)?)°?\s*,\s*(-?\d+(?:\.\d+)?)""")

private fun latLng(value: Any?): GeoPoint? {
    val text = (value as? Map<*, *>)?.let { it["latLng"] ?: it["LatLng"] } ?: value
    val match = LAT_LNG.find(text as? String ?: return null) ?: return null
    return point(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
}

private fun e7(latitude: Any?, longitude: Any?): GeoPoint? {
    val lat = (latitude as? Double) ?: return null
    val lng = (longitude as? Double) ?: return null
    return point(lat / 1e7, lng / 1e7)
}

private fun point(latitude: Double, longitude: Double) = GeoPoint(latitude, longitude).takeIf { latitude in -90.0..90.0 && longitude in -180.0..180.0 }

/** An ISO time with its offset, or the Takeout's milliseconds as text. */
private fun time(map: Map<*, *>, iso: String, ms: String): Long? =
    map.text(iso)?.let {
        try {
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    } ?: map.text(ms)?.toLongOrNull()

/** One segment of the device export: a stay, a move with its start and end, or a path of timed points. */
private fun segment(segment: Map<*, *>): List<HistoryEntry> {
    val start = time(segment, "startTime", "startTimeMs") ?: return emptyList()
    val end = time(segment, "endTime", "endTimeMs") ?: start
    val entries = mutableListOf<HistoryEntry>()
    segment.obj("visit")?.obj("topCandidate")?.let { latLng(it["placeLocation"]) }?.let { entries += HistoryEntry.Visit(start, end, it, null) }
    segment.obj("activity")?.let { activity ->
        latLng(activity["start"])?.let { entries += HistoryEntry.Point(start, it) }
        latLng(activity["end"])?.let { entries += HistoryEntry.Point(end, it) }
    }
    segment.list("timelinePath").forEach { step ->
        val at = time(step, "time", "timeMs") ?: step.text("durationMinutesOffsetFromStartTime")?.toLongOrNull()?.let { start + it * 60_000 }
        val point = latLng(step["point"])
        if (at != null && point != null) entries += HistoryEntry.Point(at, point)
    }
    return entries
}

private fun rawSignal(signal: Map<*, *>): List<HistoryEntry> {
    val position = signal.obj("position") ?: return emptyList()
    val at = time(position, "timestamp", "timestampMs") ?: return emptyList()
    return listOfNotNull(latLng(position)?.let { HistoryEntry.Point(at, it) })
}

/** One object of a Takeout month: a place visit, or an activity segment with its path. */
private fun timelineObject(item: Map<*, *>): List<HistoryEntry> {
    item.obj("placeVisit")?.let { visit ->
        val duration = visit.obj("duration") ?: return emptyList()
        val start = time(duration, "startTimestamp", "startTimestampMs") ?: return emptyList()
        val end = time(duration, "endTimestamp", "endTimestampMs") ?: start
        val location = visit.obj("location") ?: return emptyList()
        val point = e7(location["latitudeE7"], location["longitudeE7"]) ?: return emptyList()
        return listOf(HistoryEntry.Visit(start, end, point, location.text("name") ?: location.text("address")))
    }
    val activity = item.obj("activitySegment") ?: return emptyList()
    val duration = activity.obj("duration") ?: return emptyList()
    val start = time(duration, "startTimestamp", "startTimestampMs")
    val end = time(duration, "endTimestamp", "endTimestampMs")
    val entries = mutableListOf<HistoryEntry>()
    activity.obj("startLocation")?.let { e7(it["latitudeE7"], it["longitudeE7"]) }?.let { if (start != null) entries += HistoryEntry.Point(start, it) }
    activity.obj("endLocation")?.let { e7(it["latitudeE7"], it["longitudeE7"]) }?.let { if (end != null) entries += HistoryEntry.Point(end, it) }
    activity.obj("simplifiedRawPath")?.list("points")?.forEach { step ->
        val at = time(step, "timestamp", "timestampMs")
        val point = e7(step["latE7"], step["lngE7"])
        if (at != null && point != null) entries += HistoryEntry.Point(at, point)
    }
    return entries
}

/** One raw fix of the Takeout `Records.json`. */
private fun record(fix: Map<*, *>): List<HistoryEntry> {
    val at = time(fix, "timestamp", "timestampMs") ?: return emptyList()
    return listOfNotNull(e7(fix["latitudeE7"], fix["longitudeE7"])?.let { HistoryEntry.Point(at, it) })
}
