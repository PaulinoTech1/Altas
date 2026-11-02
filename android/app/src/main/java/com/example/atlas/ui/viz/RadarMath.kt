package com.example.atlas.ui.viz

import com.example.atlas.acquisition.bluetooth.BleDeviceHistory
import com.example.atlas.acquisition.bluetooth.BleObservation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One BLE device on the radar. [x]/[y] are unit-circle coordinates (radius 1 = the outer -100 dBm ring), precomputed so
 * the draw pass does no trigonometry.
 */
data class RadarDot(val address: String, val label: String, val rssiDbm: Int, val count: Long, val x: Float, val y: Float)

/** V2: pure geometry for the BLE radar. Rings and radius encode signal strength only, never distance or position. */
object RadarMath {
    const val NEAR_DBM = -30
    const val FAR_DBM = -100
    /** Labeled rings, inner to outer; the last is the plot edge. */
    val RINGS = listOf(-50, -70, -90, -100)
    const val SPARK_POINTS = 30

    /** 0 at the center (-30 dBm or stronger) to 1 at the edge (-100 dBm or weaker). */
    fun radius(rssiDbm: Int): Float = 1f - VizMath.fraction(rssiDbm.toFloat(), FAR_DBM.toFloat(), NEAR_DBM.toFloat())

    /** Stable angle in radians from the address; it only spreads dots apart and carries no direction. */
    fun angle(address: String): Float = (VizMath.stableUnit(address) * 2 * PI).toFloat()

    /** Dot radius in dp, growing with log(receivedCount) and capped so busy beacons do not cover the plot. */
    fun dotRadiusDp(count: Long): Float = (3f + 1.6f * ln(1f + count.coerceAtLeast(0L).toFloat())).coerceAtMost(12f)

    fun label(name: String?): String = name?.takeIf { it.isNotBlank() } ?: "Unnamed BLE device"

    fun dots(devices: List<BleDeviceHistory>): List<RadarDot> = devices.mapNotNull { device ->
        val event = device.latest
        val rssi = event.rssiDbm ?: return@mapNotNull null
        val r = radius(rssi)
        val a = angle(event.address)
        RadarDot(event.address, label(event.advertisement.name), rssi, device.receivedCount, r * cos(a), r * sin(a))
    }

    /** Nearest dot to a tap at unit coordinates ([x], [y]) within [slop] (also in unit radii). */
    fun hit(x: Float, y: Float, dots: List<RadarDot>, slop: Float): RadarDot? = dots
        .map { it to sqrt((it.x - x) * (it.x - x) + (it.y - y) * (it.y - y)) }
        .filter { it.second <= slop }
        .minByOrNull { it.second }?.first

    /**
     * Per-address RSSI series, oldest to newest, at most [points] each, from the newest-first in-memory history.
     * Built once per history change so rows do not each rescan the history.
     */
    fun sparklines(history: List<BleObservation>, points: Int = SPARK_POINTS): Map<String, List<Int>> {
        val out = HashMap<String, ArrayList<Int>>()
        for (event in history) {
            val rssi = event.rssiDbm ?: continue
            val series = out.getOrPut(event.address) { ArrayList() }
            if (series.size < points) series.add(rssi)
        }
        out.values.forEach { it.reverse() }
        return out
    }

    fun summary(dots: List<RadarDot>, scanning: Boolean): String {
        if (dots.isEmpty()) return "Bluetooth radar, no devices with a signal reading"
        val strongest = dots.maxBy { it.rssiDbm }
        val count = if (dots.size == 1) "1 device" else "${dots.size} devices"
        return "Bluetooth radar, $count, strongest ${strongest.rssiDbm} dBm, ${if (scanning) "scanning" else "not scanning"}. " +
            "Rings show signal strength, not distance."
    }
}
