package com.example.atlas.ui.viz

import com.example.atlas.acquisition.bluetooth.BleObservation

/** Signal-search evidence for one observed BLE address. No distance or bearing is inferred. */
object BleLocatorMath {
    private const val LIVE_NANOS = 3_000_000_000L
    private const val TREND_NANOS = 10_000_000_000L

    enum class Status { SELECT_TARGET, WAITING, LIVE, STALE, ENDED, NO_SIGNAL }

    data class Reading(
        val status: Status,
        val latestDbm: Int? = null,
        val bestDbm: Int? = null,
        val ageMillis: Long? = null,
        val sampleCount: Int = 0,
        val trendDb: Int? = null
    )

    fun evaluate(
        history: List<BleObservation>, address: String?, scanning: Boolean,
        startedAtMillis: Long?, nowElapsedNanos: Long
    ): Reading {
        if (address == null) return Reading(Status.SELECT_TARGET)
        val current = if (scanning && startedAtMillis != null) history.filter { it.observedAtMillis >= startedAtMillis } else history
        val session = current.firstOrNull()?.orientationSessionId
        val samples = current.asSequence()
            .filter { it.address == address && it.rssiDbm != null && (session == null || it.orientationSessionId == session) }
            .take(256).toList()
        if (samples.isEmpty()) return Reading(if (scanning) Status.WAITING else Status.NO_SIGNAL)

        val latest = samples.first()
        val measurementTime = latest.sourceElapsedNanos?.takeIf { it <= nowElapsedNanos } ?: latest.receivedElapsedNanos
        val ageNanos = (nowElapsedNanos - measurementTime).coerceAtLeast(0)
        val status = when {
            !scanning -> Status.ENDED
            ageNanos > LIVE_NANOS -> Status.STALE
            else -> Status.LIVE
        }
        val recent = if (status == Status.LIVE) samples.filter {
            val timestamp = it.sourceElapsedNanos?.takeIf { source -> source <= nowElapsedNanos } ?: it.receivedElapsedNanos
            nowElapsedNanos - timestamp in 0..TREND_NANOS
        } else emptyList()
        val trend = if (recent.size >= 6) median(recent.take(3)) - median(recent.drop(3).take(3)) else null
        return Reading(status, latest.rssiDbm, samples.maxOf { it.rssiDbm!! }, ageNanos / 1_000_000,
            samples.size, trend)
    }

    private fun median(samples: List<BleObservation>): Int = samples.mapNotNull { it.rssiDbm }.sorted()[1]
}
