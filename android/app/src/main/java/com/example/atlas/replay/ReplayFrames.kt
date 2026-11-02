package com.example.atlas.replay

import com.example.atlas.acquisition.cellular.CellBatch
import com.example.atlas.acquisition.cellular.CellDelivery
import com.example.atlas.acquisition.cellular.CellRecord
import com.example.atlas.acquisition.cellular.CellularMetric
import com.example.atlas.acquisition.cellular.SignalReport
import com.example.atlas.acquisition.cellular.SubscriptionSignal
import com.example.atlas.telemetry.Measurement
import com.example.atlas.ui.viz.RadarDot
import com.example.atlas.ui.viz.RadarMath
import com.example.atlas.ui.viz.SpectrumAp
import com.example.atlas.ui.viz.SpectrumMath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * One replay step rebuilt from saved rows only. Each radio shows what was saved in the [CARRY_MILLIS] before
 * [atMillis] (Wi-Fi scans are tens of seconds apart), so a frame is a faithful snapshot, never an interpolation.
 */
data class ReplayFrame(
    val atMillis: Long,
    val wifi: List<SpectrumAp>,
    val ble: List<RadarDot>,
    val cells: CellBatch?,
    val signal: SignalReport?
)

data class ReplayTimeline(val frames: List<ReplayFrame>, val hasWifi: Boolean, val hasBle: Boolean, val hasCellular: Boolean) {
    val isEmpty get() = frames.isEmpty()
}

/** V5: pure conversion of saved Measurement rows (as TelemetryNormalizer wrote them) into replay frames. */
object ReplayFrames {
    const val WINDOW_MILLIS = 5_000L
    const val CARRY_MILLIS = 30_000L
    const val MAX_FRAMES = 120
    const val MAX_ROWS = 3_000

    private val json = Json { ignoreUnknownKeys = true }

    private fun meta(row: Measurement): JsonObject =
        runCatching { json.parseToJsonElement(row.metadataJson).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    private class WifiPoint(val at: Long, val ap: SpectrumAp)
    private class BlePoint(val at: Long, val address: String, val label: String, val rssi: Int)
    private class CellPoint(val at: Long, val row: Measurement, val meta: JsonObject)

    /** Rows in any order; returns frames oldest first, at most [MAX_FRAMES] (the newest ones). */
    fun build(rows: List<Measurement>): ReplayTimeline {
        val sorted = rows.filter { it.value != null }.sortedBy { it.eventTimestampMillis }
        val wifi = wifiPoints(sorted)
        val ble = sorted.filter { it.radioType == "BLUETOOTH" && it.measurementType == "BLE RSSI" && it.interfaceId != null }.map {
            BlePoint(it.eventTimestampMillis, it.interfaceId!!, RadarMath.label(meta(it).string("name")), it.value!!.toInt())
        }
        val cell = sorted.filter { it.radioType == "CELLULAR" }.map { CellPoint(it.eventTimestampMillis, it, meta(it)) }
        val windows = (wifi.map { it.at } + ble.map { it.at } + cell.map { it.at })
            .map { it - Math.floorMod(it, WINDOW_MILLIS) + WINDOW_MILLIS - 1 }.distinct().sorted().takeLast(MAX_FRAMES)
        val frames = windows.map { end ->
            val start = end - CARRY_MILLIS
            ReplayFrame(end,
                wifiFrame(wifi.filter { it.at in start..end }),
                bleFrame(ble.filter { it.at <= end }, start),
                cellFrame(cell.filter { it.at in start..end && it.meta.string("scope") == null }),
                signalFrame(cell.filter { it.at in start..end && it.meta.string("scope") != null }))
        }
        return ReplayTimeline(frames, wifi.isNotEmpty(), ble.isNotEmpty(), cell.isNotEmpty())
    }

    /** Pairs each saved "Wi-Fi RSSI" row with its "Wi-Fi frequency" row (same BSSID and timestamp). */
    private fun wifiPoints(sorted: List<Measurement>): List<WifiPoint> {
        val wifi = sorted.filter { it.radioType == "WIFI" }
        val freq = HashMap<Pair<String?, Long>, Int>()
        wifi.filter { it.measurementType == "Wi-Fi frequency" }.forEach { freq[it.interfaceId to it.eventTimestampMillis] = it.value!!.toInt() }
        val connected = wifi.filter { it.measurementType == "Connected Wi-Fi RSSI" }.mapNotNull { r -> r.interfaceId?.let { it to r.eventTimestampMillis } }
        return wifi.filter { it.measurementType == "Wi-Fi RSSI" }.mapNotNull { row ->
            val primary = freq[row.interfaceId to row.eventTimestampMillis] ?: return@mapNotNull null
            if (SpectrumMath.bandOf(primary) == null) return@mapNotNull null
            val m = meta(row)
            val width = SpectrumMath.widthMhz(m.string("channelWidth").orEmpty())
            val isConnected = row.interfaceId != null && connected.any { (bssid, at) ->
                bssid == row.interfaceId && kotlin.math.abs(at - row.eventTimestampMillis) <= CARRY_MILLIS
            }
            // Center frequency is not saved, so replayed humps sit on the primary channel.
            WifiPoint(row.eventTimestampMillis, SpectrumAp(row.interfaceId ?: "saved-${row.dedupeKey}", SpectrumMath.label(m.string("ssid")),
                row.interfaceId, primary, primary, width, row.value!!.toInt(), m.int("channel"), isConnected))
        }
    }

    private fun wifiFrame(points: List<WifiPoint>): List<SpectrumAp> =
        points.groupBy { it.ap.key }.values.map { group -> group.maxBy { it.at }.ap }

    /** Devices seen in the carry window; dot size grows with how many saved observations led up to this frame. */
    private fun bleFrame(upToEnd: List<BlePoint>, start: Long): List<RadarDot> {
        val counts = upToEnd.groupingBy { it.address }.eachCount()
        return upToEnd.filter { it.at >= start }.groupBy { it.address }.values.map { group ->
            val latest = group.maxBy { it.at }
            val r = RadarMath.radius(latest.rssi)
            val a = RadarMath.angle(latest.address)
            RadarDot(latest.address, latest.label, latest.rssi, (counts[latest.address] ?: 1).toLong(), r * cos(a), r * sin(a))
        }
    }

    /** Rebuilds the latest saved cell batch in the window as CellRecords (grouped by cell and status). */
    private fun cellFrame(points: List<CellPoint>): CellBatch? {
        val latestAt = points.maxOfOrNull { it.at } ?: return null
        val rows = points.filter { it.at == latestAt }
            .groupBy { listOf(it.meta.string("rat"), it.row.interfaceId, it.meta.int("connectionStatus"), it.meta.bool("registered"), it.meta.int("pci")) }
            .values.map { group ->
                val m = group.first().meta
                CellRecord(m.string("rat") ?: "Unknown", m.bool("registered") ?: false, m.int("connectionStatus") ?: 0,
                    group.first().row.interfaceId?.toLongOrNull(), m.int("pci"), m.int("areaCode"), m.int("channel"), null, null,
                    group.map { CellularMetric(it.row.measurementType, it.row.value?.toInt(), it.row.units.orEmpty()) }, null)
            }
        return CellBatch("replay-$latestAt", rows, rows.size, null, CellDelivery.CALLBACK, latestAt, 0L, 0L, "Saved telemetry (replay)")
    }

    private fun signalFrame(points: List<CellPoint>): SignalReport? {
        val latestAt = points.maxOfOrNull { it.at } ?: return null
        val signals = points.filter { it.at == latestAt }.groupBy { it.meta.string("rat") ?: "Unknown" }.map { (rat, group) ->
            SubscriptionSignal(rat, group.map { CellularMetric(it.row.measurementType, it.row.value?.toInt(), it.row.units.orEmpty()) })
        }
        return SignalReport(signals, latestAt, 0L, null)
    }

    /** Frames up to and including [index], newest first, for charts that need a history (gauge line). */
    fun historyAt(frames: List<ReplayFrame>, index: Int): List<ReplayFrame> =
        frames.subList(0, (index + 1).coerceIn(0, frames.size)).asReversed()
}
