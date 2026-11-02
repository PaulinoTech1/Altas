package com.example.atlas

import com.example.atlas.telemetry.BucketCount
import com.example.atlas.ui.viz.DailyCounts
import com.example.atlas.ui.viz.DayBar
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DailyCountsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun bucket(local: LocalDateTime, radio: String, count: Int) =
        BucketCount(local.atZone(zone).toInstant().toEpochMilli() / DailyCounts.BUCKET_MILLIS, radio, count)

    @Test fun regroupsBucketsIntoLocalDaysZeroFilled() {
        val today = LocalDate.of(2026, 11, 1)
        val buckets = listOf(
            bucket(LocalDateTime.of(2026, 11, 1, 0, 5), "WIFI", 3),
            bucket(LocalDateTime.of(2026, 10, 31, 23, 50), "WIFI", 2), // UTC Nov 1, local Oct 31
            bucket(LocalDateTime.of(2026, 10, 31, 12, 0), "BLUETOOTH", 4),
            bucket(LocalDateTime.of(2026, 10, 31, 12, 15), "BLUETOOTH", 1),
            bucket(LocalDateTime.of(2026, 10, 1, 12, 0), "WIFI", 99), // outside 14 days
            bucket(LocalDateTime.of(2026, 11, 1, 1, 0), "UNKNOWN", 7)
        )
        val bars = DailyCounts.byDay(buckets, zone, today.toEpochDay())
        assertEquals(14, bars.size)
        assertEquals(today.toEpochDay(), bars.last().epochDay)
        assertEquals(today.minusDays(13).toEpochDay(), bars.first().epochDay)
        assertEquals(mapOf("WIFI" to 3), bars.last().counts)
        assertEquals(mapOf("WIFI" to 2, "BLUETOOTH" to 5), bars[12].counts)
        assertEquals(0, bars[0].total)
    }

    @Test fun dstFallBackDayStillGroupsByLocalDate() {
        // 2026-11-01 01:30 happens twice in New York; both belong to Nov 1.
        val day = LocalDate.of(2026, 11, 1)
        val first = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val second = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        val bars = DailyCounts.byDay(listOf(BucketCount(first / 900_000, "NFC", 1), BucketCount(second / 900_000, "NFC", 1)), zone, day.toEpochDay(), 2)
        assertEquals(2, bars.last().counts["NFC"])
    }

    @Test fun segmentHitFollowsStackOrder() {
        val bar = DayBar(0, mapOf("WIFI" to 5, "CELLULAR" to 5))
        assertEquals("WIFI", DailyCounts.segmentAt(bar, 0.1f, 10))
        assertEquals("CELLULAR", DailyCounts.segmentAt(bar, 0.9f, 10))
        assertNull(DailyCounts.segmentAt(DayBar(0, mapOf("WIFI" to 5)), 0.9f, 10))
        assertNull(DailyCounts.segmentAt(bar, 0.5f, 0))
    }

    @Test fun summaryNamesTotalsAndBusiestDay() {
        val bars = listOf(DayBar(LocalDate.of(2026, 10, 31).toEpochDay(), mapOf("WIFI" to 2)),
            DayBar(LocalDate.of(2026, 11, 1).toEpochDay(), mapOf("WIFI" to 1, "NFC" to 4)))
        assertEquals("Saved records per day, last 2 days, 7 total (Wi-Fi 3, NFC 4), busiest 2026-11-01 with 5", DailyCounts.summary(bars))
        assertEquals("Saved records per day, last 1 days, none saved in this period", DailyCounts.summary(listOf(DayBar(0, emptyMap()))))
    }
}
