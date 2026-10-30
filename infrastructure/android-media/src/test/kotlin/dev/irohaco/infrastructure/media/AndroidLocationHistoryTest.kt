package dev.irohaco.infrastructure.media

import dev.irohaco.core.contracts.HistoryEntry
import dev.irohaco.core.contracts.UnreadableHistoryException
import dev.irohaco.core.contracts.UnreadableHistoryException.Reason
import dev.irohaco.core.domain.GeoPoint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The location history files Google writes, read entry by entry (ADR-0029). */
@RunWith(RobolectricTestRunner::class)
class AndroidLocationHistoryTest {
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun read(json: String, keep: (Long, Long) -> Boolean = { _, _ -> true }) = readHistory(json.reader(), keep)

    @Test
    fun `the device export gives stays, moves and paths`() {
        val json = """
            {"semanticSegments": [
              {"startTime": "2024-08-03T10:00:00.000+09:00", "endTime": "2024-08-03T12:00:00.000+09:00",
               "visit": {"probability": 0.9, "topCandidate": {"placeId": "x", "placeLocation": {"latLng": "35.0116°, 135.7681°"}}}},
              {"startTime": "2024-08-03T12:00:00.000+09:00", "endTime": "2024-08-03T13:00:00.000+09:00",
               "activity": {"start": {"latLng": "35.0116°, 135.7681°"}, "end": {"latLng": "34.9858°, 135.7588°"}}},
              {"startTime": "2024-08-03T12:00:00.000+09:00", "endTime": "2024-08-03T14:00:00.000+09:00",
               "timelinePath": [{"point": "35.0°, 135.75°", "time": "2024-08-03T12:30:00.000+09:00"}]}
            ],
            "rawSignals": [{"position": {"LatLng": "35.1°, 135.8°", "timestamp": "2024-08-03T15:00:00.000+09:00"}}],
            "userLocationProfile": {"frequentPlaces": []}}
        """
        assertEquals(
            listOf(
                HistoryEntry.Visit(ms("2024-08-03T10:00:00+09:00"), ms("2024-08-03T12:00:00+09:00"), GeoPoint(35.0116, 135.7681), null),
                HistoryEntry.Point(ms("2024-08-03T12:00:00+09:00"), GeoPoint(35.0116, 135.7681)),
                HistoryEntry.Point(ms("2024-08-03T13:00:00+09:00"), GeoPoint(34.9858, 135.7588)),
                HistoryEntry.Point(ms("2024-08-03T12:30:00+09:00"), GeoPoint(35.0, 135.75)),
                HistoryEntry.Point(ms("2024-08-03T15:00:00+09:00"), GeoPoint(35.1, 135.8)),
            ),
            read(json),
        )
    }

    @Test
    fun `the array form of the device export reads too`() {
        val json = """[{"startTime": "2024-08-03T10:00:00.000+09:00", "endTime": "2024-08-03T11:00:00.000+09:00",
            "visit": {"topCandidate": {"placeLocation": "geo:35.0116,135.7681"}}},
            {"startTime": "2024-08-03T11:00:00.000+09:00", "endTime": "2024-08-03T12:00:00.000+09:00",
            "timelinePath": [{"point": "geo:35.0,135.75", "durationMinutesOffsetFromStartTime": "30"}]}]"""
        assertEquals(
            listOf(
                HistoryEntry.Visit(ms("2024-08-03T10:00:00+09:00"), ms("2024-08-03T11:00:00+09:00"), GeoPoint(35.0116, 135.7681), null),
                HistoryEntry.Point(ms("2024-08-03T11:30:00+09:00"), GeoPoint(35.0, 135.75)),
            ),
            read(json),
        )
    }

    @Test
    fun `the old Takeout files read, with the place names they carry`() {
        val semantic = """{"timelineObjects": [
            {"placeVisit": {"location": {"latitudeE7": 350116000, "longitudeE7": 1357681000, "name": "Kyoto Station", "address": "Kyoto"},
              "duration": {"startTimestamp": "2019-05-01T01:00:00Z", "endTimestamp": "2019-05-01T02:00:00Z"}}},
            {"activitySegment": {"startLocation": {"latitudeE7": 350116000, "longitudeE7": 1357681000},
              "endLocation": {"latitudeE7": 349858000, "longitudeE7": 1357588000},
              "duration": {"startTimestampMs": "1556672400000", "endTimestampMs": "1556676000000"},
              "simplifiedRawPath": {"points": [{"latE7": 350000000, "lngE7": 1357500000, "timestamp": "2019-05-01T02:30:00Z"}]}}}
        ]}"""
        assertEquals(
            listOf(
                HistoryEntry.Visit(ms("2019-05-01T01:00:00Z"), ms("2019-05-01T02:00:00Z"), GeoPoint(35.0116, 135.7681), "Kyoto Station"),
                HistoryEntry.Point(1556672400000, GeoPoint(35.0116, 135.7681)),
                HistoryEntry.Point(1556676000000, GeoPoint(34.9858, 135.7588)),
                HistoryEntry.Point(ms("2019-05-01T02:30:00Z"), GeoPoint(35.0, 135.75)),
            ),
            read(semantic),
        )
        val records = """{"locations": [{"latitudeE7": 350116000, "longitudeE7": 1357681000, "timestampMs": "1556672400000"},
            {"latitudeE7": 349858000, "longitudeE7": 1357588000, "timestamp": "2019-05-01T02:00:00Z"}]}"""
        assertEquals(
            listOf(HistoryEntry.Point(1556672400000, GeoPoint(35.0116, 135.7681)), HistoryEntry.Point(ms("2019-05-01T02:00:00Z"), GeoPoint(34.9858, 135.7588))),
            read(records),
        )
    }

    @Test
    fun `only the entries kept stay in memory`() {
        val records = """{"locations": [{"latitudeE7": 350000000, "longitudeE7": 1350000000, "timestampMs": "1000"},
            {"latitudeE7": 360000000, "longitudeE7": 1360000000, "timestampMs": "2000"}]}"""
        assertEquals(listOf(HistoryEntry.Point(2000, GeoPoint(36.0, 136.0))), read(records) { start, _ -> start > 1500 })
    }

    @Test
    fun `a file that is not a history says why and keeps nothing`() {
        assertEquals(Reason.NOT_JSON, assertFailsWith<UnreadableHistoryException> { read("not json {") }.reason)
        assertEquals(Reason.UNKNOWN_FORMAT, assertFailsWith<UnreadableHistoryException> { read("""{"photos": []}""") }.reason)
        assertEquals(Reason.EMPTY, assertFailsWith<UnreadableHistoryException> { read("""{"semanticSegments": []}""") }.reason)
    }
}
