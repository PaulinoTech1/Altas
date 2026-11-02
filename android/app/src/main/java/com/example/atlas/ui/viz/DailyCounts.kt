package com.example.atlas.ui.viz

import com.example.atlas.telemetry.BucketCount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Saved records on one local day, by radioType. */
data class DayBar(val epochDay: Long, val counts: Map<String, Int>) {
    val total: Int get() = counts.values.sum()
}

/** V4: pure regrouping of 15-minute SQL buckets into local-day stacked bars for the History chart. */
object DailyCounts {
    const val BUCKET_MILLIS = 900_000L
    const val DAYS = 14
    /** Stacking order, bottom to top; matches the History filter chips' radio values. */
    val RADIOS = listOf("WIFI", "BLUETOOTH", "CELLULAR", "NFC")
    val TITLES = mapOf("WIFI" to "Wi-Fi", "BLUETOOTH" to "Bluetooth", "CELLULAR" to "Cellular", "NFC" to "NFC")

    fun epochDay(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /** Exactly [days] bars ending on [todayEpochDay], oldest first, zero-filled; unknown radio types are ignored. */
    fun byDay(buckets: List<BucketCount>, zone: ZoneId, todayEpochDay: Long, days: Int = DAYS): List<DayBar> {
        val first = todayEpochDay - days + 1
        val sums = HashMap<Long, HashMap<String, Int>>()
        for (b in buckets) {
            if (b.radioType !in RADIOS || b.count <= 0) continue
            val day = epochDay(b.bucket * BUCKET_MILLIS, zone)
            if (day < first || day > todayEpochDay) continue
            val m = sums.getOrPut(day) { HashMap() }
            m[b.radioType] = (m[b.radioType] ?: 0) + b.count
        }
        return (first..todayEpochDay).map { DayBar(it, sums[it].orEmpty()) }
    }

    /** Radio whose stacked segment covers height [fromBottom] (0..1 of the plot, scaled to [maxTotal]), if any. */
    fun segmentAt(bar: DayBar, fromBottom: Float, maxTotal: Int): String? {
        if (maxTotal <= 0) return null
        var acc = 0f
        for (radio in RADIOS) {
            val c = bar.counts[radio] ?: 0
            if (c == 0) continue
            acc += c.toFloat() / maxTotal
            if (fromBottom <= acc) return radio
        }
        return null
    }

    fun summary(bars: List<DayBar>): String {
        val total = bars.sumOf { it.total }
        if (total == 0) return "Saved records per day, last ${bars.size} days, none saved in this period"
        val busiest = bars.maxBy { it.total }
        val perRadio = RADIOS.mapNotNull { r -> bars.sumOf { it.counts[r] ?: 0 }.takeIf { it > 0 }?.let { "${TITLES[r]} $it" } }
        return "Saved records per day, last ${bars.size} days, $total total (${perRadio.joinToString(", ")}), " +
            "busiest ${LocalDate.ofEpochDay(busiest.epochDay)} with ${busiest.total}"
    }
}
