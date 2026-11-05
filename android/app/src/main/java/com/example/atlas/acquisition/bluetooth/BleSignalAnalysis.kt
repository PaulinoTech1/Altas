package com.example.atlas.acquisition.bluetooth

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Session-local evidence about the phone orientation at receipt, never transmitter bearing. */
object BleSignalAnalysis {
    data class Sector(val azimuthBin: Int, val elevationBin: Int, val count: Int,
                      val medianDbm: Int, val spreadDb: Int, val x: Float, val y: Float, val z: Float)
    data class Summary(val sessionSamples: Int, val targetSamples: Int, val pairedSamples: Int,
                       val missingRssi: Int, val missingOrientation: Int, val missingSourceTime: Int,
                       val sequenceGapsAtLeast: Long, val sectors: List<Sector>) {
        val comparableSectors: List<Sector> get() = sectors.filter { it.count >= 3 && it.spreadDb <= 8 }
        val repeatableCoverage: Boolean get() = comparableSectors.map { it.azimuthBin / 3 }.distinct().size == 4
    }

    fun summarize(history: List<BleObservation>, address: String?): Summary {
        val session = history.firstOrNull()?.orientationSessionId
        val current = if (session == null) emptyList() else history.filter { it.orientationSessionId == session }
        val target = if (address == null) emptyList() else current.filter { it.address == address }
        val gaps = current.mapNotNull { it.scanSequence }.sorted().zipWithNext()
            .sumOf { (first, next) -> (next - first - 1).coerceAtLeast(0) }
        val groups = target.asSequence().filter { it.rssiDbm != null && it.phoneOrientation != null }
            .mapNotNull { event ->
                val orientation = event.phoneOrientation!!
                val length = sqrt(orientation.x * orientation.x + orientation.y * orientation.y + orientation.z * orientation.z)
                if (!length.isFinite() || length < 0.5f) return@mapNotNull null
                val x = orientation.x / length
                val y = orientation.y / length
                val z = orientation.z / length
                val azimuth = Math.toDegrees(atan2(z.toDouble(), x.toDouble())).toInt()
                val elevation = Math.toDegrees(asin(y.coerceIn(-1f, 1f).toDouble())).toInt()
                Triple(((azimuth + 180) % 360) / 30 to (elevation + 90) / 30, event.rssiDbm!!, floatArrayOf(x, y, z))
            }.groupBy { it.first }
        val sectors = groups.map { (key, values) ->
            val median = median(values.map { it.second })
            val spread = median(values.map { kotlin.math.abs(it.second - median) })
            val x = values.map { it.third[0] }.average().toFloat()
            val y = values.map { it.third[1] }.average().toFloat()
            val z = values.map { it.third[2] }.average().toFloat()
            Sector(key.first, key.second, values.size, median, spread, x, y, z)
        }.sortedWith(compareByDescending<Sector> { it.count }.thenBy { it.azimuthBin }.thenBy { it.elevationBin })
        return Summary(current.size, target.size, target.count { it.rssiDbm != null && it.phoneOrientation != null },
            target.count { it.rssiDbm == null }, target.count { it.phoneOrientation == null },
            target.count { it.sourceElapsedNanos == null }, gaps, sectors)
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else ((sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0).roundToInt()
    }
}
