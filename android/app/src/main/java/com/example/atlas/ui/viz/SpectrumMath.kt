package com.example.atlas.ui.viz

import com.example.atlas.acquisition.wifi.WifiConnection
import com.example.atlas.acquisition.wifi.WifiObservation
import com.example.atlas.acquisition.wifi.WifiValues

/** One access point as the spectrum draws it. [key] is the BSSID when available, otherwise the row's event id. */
data class SpectrumAp(
    val key: String,
    val label: String,
    val bssid: String?,
    val primaryMhz: Int,
    val centerMhz: Int,
    val widthMhz: Int,
    val rssiDbm: Int,
    val channel: Int?,
    val connected: Boolean
)

/** V1: pure geometry for the Wi-Fi spectrum (frequency to x, width, channel ticks, hump height, hit testing). */
object SpectrumMath {
    const val MIN_DBM = -100
    const val MAX_DBM = -30

    enum class Band(val label: String, val startMhz: Int, val endMhz: Int, val tickChannels: List<Int>) {
        GHZ_2_4("2.4 GHz", 2400, 2495, listOf(1, 6, 11, 14)),
        GHZ_5("5 GHz", 5150, 5895, listOf(36, 52, 100, 116, 132, 149, 165)),
        GHZ_6("6 GHz", 5925, 7125, listOf(1, 37, 69, 101, 133, 165, 197, 229));

        val spanMhz get() = (endMhz - startMhz).toFloat()
    }

    /** Band containing a primary frequency; 60 GHz and unknown frequencies are not drawn. */
    fun bandOf(frequencyMhz: Int): Band? = when (frequencyMhz) {
        in 2400..2495 -> Band.GHZ_2_4
        in 5150..5895 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        else -> null
    }

    /** Channel center frequency for a band's channel number (ticks). */
    fun channelMhz(band: Band, channel: Int): Int = when (band) {
        Band.GHZ_2_4 -> if (channel == 14) 2484 else 2407 + channel * 5
        Band.GHZ_5 -> 5000 + channel * 5
        Band.GHZ_6 -> 5950 + channel * 5
    }

    /** Ticks for a band as (channel, frequency) pairs. */
    fun ticks(band: Band): List<Pair<Int, Int>> = band.tickChannels.map { it to channelMhz(band, it) }

    /**
     * Width in MHz from the app's channelWidth label ("20 MHz", "80+80 MHz", "Unavailable / unsupported (7)").
     * 80+80 draws its primary 80 MHz segment; unknown widths draw as 20 MHz.
     */
    fun widthMhz(label: String): Int {
        val digits = label.takeWhile { it.isDigit() }
        return digits.toIntOrNull()?.takeIf { it in setOf(20, 40, 80, 160, 320) } ?: 20
    }

    /** Center of the occupied channel: centerFrequency0 when Android reports one in-band, otherwise the primary. */
    fun centerMhz(primaryMhz: Int, center0Mhz: Int?, widthMhz: Int): Int =
        center0Mhz?.takeIf { it > 0 && widthMhz > 20 && bandOf(it) == bandOf(primaryMhz) && kotlin.math.abs(it - primaryMhz) <= widthMhz / 2 }
            ?: primaryMhz

    /** Horizontal position (0..1, may fall outside for edge humps) of a frequency within [band]. */
    fun x(frequencyMhz: Float, band: Band): Float = (frequencyMhz - band.startMhz) / band.spanMhz

    /** Hump height as a fraction of the plot (0 at -100 dBm, 1 at -30 dBm). */
    fun height(rssiDbm: Int): Float = VizMath.fraction(rssiDbm.toFloat(), MIN_DBM.toFloat(), MAX_DBM.toFloat())

    /**
     * Hump profile for offset [d] from the center in half-widths: a smooth bell that is 1 at the center and exactly 0 at
     * the channel edges ((1 - d^2)^2), so neighbouring channels do not appear to bleed beyond their width.
     */
    fun profile(d: Float): Float = if (d <= -1f || d >= 1f) 0f else (1f - d * d).let { it * it }

    /** The AP whose hump is highest at horizontal position [xFraction] in [band], if any covers it. */
    fun hit(xFraction: Float, aps: List<SpectrumAp>, band: Band): SpectrumAp? = aps
        .filter { bandOf(it.primaryMhz) == band }
        .map { ap ->
            val half = ap.widthMhz / 2f / band.spanMhz
            val d = (xFraction - x(ap.centerMhz.toFloat(), band)) / half
            ap to profile(d) * height(ap.rssiDbm)
        }
        .filter { it.second > 0f }
        .maxByOrNull { it.second }?.first

    /** Display label using the access-point list's rule, so a hidden or redacted SSID reads the same in both places. */
    fun label(ssid: String?): String = ssid ?: "SSID hidden / unavailable"

    /** Live rows to spectrum APs. Rows without RSSI or a drawable frequency are skipped (they stay in the list). */
    fun fromRows(rows: List<WifiObservation>, connections: List<WifiConnection>): List<SpectrumAp> {
        val connected = connections.mapNotNull { it.bssid }.toSet()
        val seen = HashSet<String>()
        return rows.mapNotNull { row ->
            val rssi = row.rssiDbm ?: return@mapNotNull null
            val primary = row.frequencyMhz ?: return@mapNotNull null
            if (bandOf(primary) == null) return@mapNotNull null
            val key = row.bssid ?: row.eventId
            if (!seen.add(key)) return@mapNotNull null
            val width = widthMhz(row.channelWidth)
            SpectrumAp(key, label(row.ssid), row.bssid, primary, centerMhz(primary, row.centerFrequency0Mhz, width), width, rssi,
                row.channel ?: WifiValues.channel(primary), row.bssid != null && row.bssid in connected)
        }
    }

    /** "Wi-Fi spectrum, 5 GHz, 12 networks, strongest -42 dBm on channel 36". */
    fun summary(aps: List<SpectrumAp>, band: Band): String {
        val inBand = aps.filter { bandOf(it.primaryMhz) == band }
        val strongest = inBand.maxByOrNull { it.rssiDbm }
            ?: return "Wi-Fi spectrum, ${band.label}, no networks reported in this band"
        val count = if (inBand.size == 1) "1 network" else "${inBand.size} networks"
        return "Wi-Fi spectrum, ${band.label}, $count, strongest ${strongest.rssiDbm} dBm" +
            (strongest.channel?.let { " on channel $it" } ?: "")
    }

    /** Band to open first: the one with the most APs (ties prefer 5, then 6, then 2.4 GHz). */
    fun defaultBand(aps: List<SpectrumAp>): Band {
        val counts = aps.groupingBy { bandOf(it.primaryMhz) }.eachCount()
        return listOf(Band.GHZ_5, Band.GHZ_6, Band.GHZ_2_4).maxByOrNull { counts[it] ?: 0 }
            ?.takeIf { (counts[it] ?: 0) > 0 } ?: Band.GHZ_2_4
    }

    /** One hump in flight between two scans: height and opacity tween from/to as the animation progress goes 0..1. */
    data class Tween(val ap: SpectrumAp, val fromHeight: Float, val toHeight: Float, val fromAlpha: Float, val toAlpha: Float) {
        fun height(progress: Float) = fromHeight + (toHeight - fromHeight) * progress
        fun alpha(progress: Float) = fromAlpha + (toAlpha - fromAlpha) * progress
    }

    /**
     * Retargets the in-flight humps at [progress] to a new scan: surviving APs grow or shrink from where they are now,
     * new APs rise from zero, and APs missing from the scan keep their last shape and fade out. Fully faded ones drop.
     */
    fun retarget(current: List<Tween>, progress: Float, next: List<SpectrumAp>): List<Tween> {
        val now = current.associateBy { it.ap.key }
        val nextKeys = next.mapTo(HashSet()) { it.key }
        val kept = next.map { ap ->
            val previous = now[ap.key]
            Tween(ap, previous?.height(progress) ?: 0f, height(ap.rssiDbm), previous?.alpha(progress) ?: 0f, 1f)
        }
        val fading = current.filter { it.ap.key !in nextKeys }.mapNotNull { old ->
            val alpha = old.alpha(progress)
            if (alpha <= 0.01f) null else old.height(progress).let { h -> Tween(old.ap, h, h, alpha, 0f) }
        }
        return fading + kept
    }
}
