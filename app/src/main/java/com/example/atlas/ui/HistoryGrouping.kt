package com.example.atlas.ui

import java.time.Instant
import java.time.ZoneId

/** One run of records from the same local day, in list order. */
data class DayGroup<T>(val epochDay: Long, val items: List<T>)

/** B4: pure grouping for History's sticky day headers. No Android dependencies so it stays JVM-testable. */
object HistoryGrouping {
    fun epochDay(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /** Consecutive runs per local day; the list is newest-first already, so each day normally appears once. */
    fun <T> byDay(items: List<T>, zone: ZoneId, timeOf: (T) -> Long): List<DayGroup<T>> {
        val groups = mutableListOf<DayGroup<T>>()
        var day: Long? = null
        var run = mutableListOf<T>()
        items.forEach { item ->
            val itemDay = epochDay(timeOf(item), zone)
            if (day != null && itemDay != day) { groups += DayGroup(day!!, run); run = mutableListOf() }
            day = itemDay
            run += item
        }
        day?.let { groups += DayGroup(it, run) }
        return groups
    }

    /** "Today", "Yesterday", otherwise the caller's localized date. */
    fun dayLabel(epochDay: Long, todayEpochDay: Long, formattedDate: String): String = when (todayEpochDay - epochDay) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> formattedDate
    }
}
