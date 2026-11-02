package com.example.atlas.ui.viz

import com.example.atlas.acquisition.cellular.CellBatch
import com.example.atlas.acquisition.cellular.CellRecord
import com.example.atlas.acquisition.cellular.CellularMetric
import com.example.atlas.acquisition.cellular.SignalReport

/** Rule-of-thumb quality bands for the dials. They are display guides, not carrier or 3GPP thresholds. */
enum class SignalQuality(val label: String) { POOR("Poor"), FAIR("Fair"), GOOD("Good"), EXCELLENT("Excellent") }

/** Dial range and band edges: values below [edges][0] are POOR, below [edges][1] FAIR, below [edges][2] GOOD. */
data class GaugeSpec(val title: String, val min: Float, val max: Float, val unit: String, val edges: List<Float>)

/** One dial: [value] null renders "Not reported". [metricName] is the metric it was mapped from, when present. */
data class GaugeReading(val spec: GaugeSpec, val metricName: String?, val value: Int?)

/** Where the dials came from: the serving/registered cell row or the subscription signal callback. */
data class GaugeSet(val source: String, val rat: String, val readings: List<GaugeReading>) {
    val primary: GaugeReading? get() = readings.firstOrNull()
}

data class SeriesPoint(val atMillis: Long, val value: Int)

/** V3: pure mapping for the cellular gauges and the bounded primary-metric series. */
object GaugeMath {
    const val SWEEP_DEGREES = 270f
    /** Canvas angle (clockwise from 3 o'clock) where the 270° dial starts, bottom-left. */
    const val START_DEGREES = 135f
    const val SERIES_MAX = 120

    val RSRP = GaugeSpec("RSRP", -140f, -44f, "dBm", listOf(-110f, -100f, -90f))
    val RSRQ = GaugeSpec("RSRQ", -20f, -3f, "dB", listOf(-15f, -10f, -6f))
    val SINR = GaugeSpec("SINR", -20f, 30f, "dB", listOf(0f, 13f, 20f))
    val SIGNAL = GaugeSpec("Signal", -120f, -50f, "dBm", listOf(-105f, -95f, -85f))

    /** Maps a metric by name: LTE/NR RSRP, RSRQ and SINR (LTE reports SINR as RSSNR); other RATs' "Reported signal". */
    fun spec(name: String): GaugeSpec? = when {
        "RSRP" in name -> RSRP
        "RSRQ" in name -> RSRQ
        "SINR" in name || "RSSNR" in name -> SINR
        name == "Reported signal" -> SIGNAL
        else -> null
    }

    fun fraction(value: Int, spec: GaugeSpec): Float = VizMath.fraction(value.toFloat(), spec.min, spec.max)

    fun sweep(value: Int, spec: GaugeSpec): Float = fraction(value, spec) * SWEEP_DEGREES

    fun quality(value: Int, spec: GaugeSpec): SignalQuality = when {
        value < spec.edges[0] -> SignalQuality.POOR
        value < spec.edges[1] -> SignalQuality.FAIR
        value < spec.edges[2] -> SignalQuality.GOOD
        else -> SignalQuality.EXCELLENT
    }

    /** Dials for one metric list: RSRP/RSRQ/SINR when any of them is present, otherwise the generic signal dial. */
    fun readings(metrics: List<CellularMetric>): List<GaugeReading> {
        val mapped = metrics.mapNotNull { m -> spec(m.name)?.let { it to m } }
        val lteNr = listOf(RSRP, RSRQ, SINR)
        return if (mapped.any { it.first in lteNr }) lteNr.map { spec ->
            val metric = mapped.firstOrNull { it.first == spec }?.second
            GaugeReading(spec, metric?.name, metric?.value)
        } else {
            val metric = mapped.firstOrNull { it.first == SIGNAL }?.second
            listOf(GaugeReading(SIGNAL, metric?.name, metric?.value))
        }
    }

    /** Serving row: primary serving (connectionStatus 1), else the first registered row with any value. */
    fun servingRow(batch: CellBatch?): CellRecord? {
        val rows = batch?.rows.orEmpty().filter { row -> row.metrics.any { it.value != null } }
        return rows.firstOrNull { it.connectionStatus == 1 } ?: rows.firstOrNull { it.registered }
    }

    /** Current dials: serving cell first (cell-specific), else the subscription signal report, else null. */
    fun current(cells: CellBatch?, signal: SignalReport?): GaugeSet? {
        servingRow(cells)?.let { return GaugeSet("Serving cell", it.rat, readings(it.metrics)) }
        val reported = signal?.signals?.firstOrNull { s -> s.metrics.any { it.value != null } } ?: return null
        return GaugeSet("Subscription signal report", reported.rat, readings(reported.metrics))
    }

    /**
     * Series of the primary dial's metric from the bounded histories (both newest first), oldest to newest, at most
     * [max] points. Uses the same source as [set] so a cell-row RSRP is never spliced with a subscription-level RSRP.
     */
    fun series(set: GaugeSet?, history: List<CellBatch>, signalHistory: List<SignalReport>, max: Int = SERIES_MAX): List<SeriesPoint> {
        val name = set?.primary?.metricName ?: return emptyList()
        val points = if (set.source == "Serving cell") {
            history.mapNotNull { batch ->
                servingRow(batch)?.takeIf { it.rat == set.rat }?.metrics?.firstOrNull { it.name == name }?.value
                    ?.let { SeriesPoint(batch.receivedAtMillis, it) }
            }
        } else {
            signalHistory.mapNotNull { report ->
                report.signals.firstOrNull { it.rat == set.rat }?.metrics?.firstOrNull { it.name == name }?.value
                    ?.let { SeriesPoint(report.receivedAtMillis, it) }
            }
        }
        return points.take(max).sortedBy { it.atMillis }
    }

    fun describe(reading: GaugeReading): String = reading.value?.let {
        "${reading.spec.title} $it ${reading.spec.unit}, ${quality(it, reading.spec).label}"
    } ?: "${reading.spec.title} not reported"
}
