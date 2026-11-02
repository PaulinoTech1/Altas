package com.example.atlas

import com.example.atlas.ui.HistoryGrouping
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HistoryGroupingTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 11, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun groupsNewestFirstRowsByLocalDay() {
        val rows = listOf(at(2, 9), at(2, 1), at(1, 23), at(1, 0), at(1, 0) - 1) // last one is Oct 31
        val groups = HistoryGrouping.byDay(rows, zone) { it }
        assertEquals(listOf(2, 2, 1), groups.map { it.items.size })
        assertEquals(1L, groups[0].epochDay - groups[1].epochDay)
        assertEquals(rows, groups.flatMap { it.items })
    }

    @Test fun usesTheLocalZoneNotUtc() {
        // 23:00 New York on Nov 1 is already Nov 2 in UTC; it must stay with Nov 1.
        val groups = HistoryGrouping.byDay(listOf(at(1, 23), at(1, 8)), zone) { it }
        assertEquals(1, groups.size)
    }

    @Test fun emptyListHasNoGroups() {
        assertEquals(0, HistoryGrouping.byDay(emptyList<Long>(), zone) { it }.size)
    }

    @Test fun labelsTodayAndYesterday() {
        assertEquals("Today", HistoryGrouping.dayLabel(100, 100, "Nov 1, 2026"))
        assertEquals("Yesterday", HistoryGrouping.dayLabel(99, 100, "Oct 31, 2026"))
        assertEquals("Oct 30, 2026", HistoryGrouping.dayLabel(98, 100, "Oct 30, 2026"))
    }
}
