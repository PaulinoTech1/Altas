package com.example.atlas

import com.example.atlas.acquisition.cellular.*
import com.example.atlas.ui.viz.GaugeMath
import com.example.atlas.ui.viz.SignalQuality
import org.junit.Assert.*
import org.junit.Test

class GaugeMathTest {
    private fun lte(rsrp: Int?, rsrq: Int? = -9, sinr: Int? = 12) = listOf(
        CellularMetric("LTE RSRP", rsrp, "dBm"), CellularMetric("LTE RSRQ", rsrq, "dB"), CellularMetric("LTE RSSNR", sinr, "dB"))
    private fun row(rat: String, registered: Boolean, status: Int, metrics: List<CellularMetric>) =
        CellRecord(rat, registered, status, null, null, null, null, null, null, metrics, null)
    private fun batch(at: Long, vararg rows: CellRecord) =
        CellBatch("b$at", rows.toList(), rows.size, null, CellDelivery.CALLBACK, at, 0L, 0L, "test")
    private fun report(at: Long, rat: String, metrics: List<CellularMetric>) =
        SignalReport(listOf(SubscriptionSignal(rat, metrics)), at, 0L, null)

    @Test fun specsMapByName() {
        assertEquals(GaugeMath.RSRP, GaugeMath.spec("LTE RSRP"))
        assertEquals(GaugeMath.RSRP, GaugeMath.spec("NR SS-RSRP"))
        assertEquals(GaugeMath.RSRQ, GaugeMath.spec("NR SS-RSRQ"))
        assertEquals(GaugeMath.SINR, GaugeMath.spec("NR SS-SINR"))
        assertEquals(GaugeMath.SINR, GaugeMath.spec("LTE RSSNR"))
        assertEquals(GaugeMath.SIGNAL, GaugeMath.spec("Reported signal"))
        assertNull(GaugeMath.spec("Timing advance"))
        assertEquals(-140f, GaugeMath.RSRP.min, 0f); assertEquals(-44f, GaugeMath.RSRP.max, 0f)
        assertEquals(-20f, GaugeMath.RSRQ.min, 0f); assertEquals(-3f, GaugeMath.RSRQ.max, 0f)
        assertEquals(-20f, GaugeMath.SINR.min, 0f); assertEquals(30f, GaugeMath.SINR.max, 0f)
    }

    @Test fun sweepClampsAndQualityBands() {
        assertEquals(0f, GaugeMath.sweep(-150, GaugeMath.RSRP), 0f)
        assertEquals(270f, GaugeMath.sweep(-30, GaugeMath.RSRP), 0f)
        assertEquals(135f, GaugeMath.sweep(-92, GaugeMath.RSRP), 1e-4f)
        assertEquals(SignalQuality.POOR, GaugeMath.quality(-115, GaugeMath.RSRP))
        assertEquals(SignalQuality.FAIR, GaugeMath.quality(-105, GaugeMath.RSRP))
        assertEquals(SignalQuality.GOOD, GaugeMath.quality(-95, GaugeMath.RSRP))
        assertEquals(SignalQuality.EXCELLENT, GaugeMath.quality(-80, GaugeMath.RSRP))
        assertEquals(SignalQuality.EXCELLENT, GaugeMath.quality(-90, GaugeMath.RSRP))
    }

    @Test fun readingsKeepNullAsNotReportedAndFallBackToGenericSignal() {
        val r = GaugeMath.readings(lte(-95, rsrq = null))
        assertEquals(listOf("RSRP", "RSRQ", "SINR"), r.map { it.spec.title })
        assertNull(r[1].value)
        assertEquals("RSRQ not reported", GaugeMath.describe(r[1]))
        assertEquals("RSRP -95 dBm, Good", GaugeMath.describe(r[0]))
        val gsm = GaugeMath.readings(listOf(CellularMetric("Reported signal", -85, "dBm")))
        assertEquals(listOf("Signal"), gsm.map { it.spec.title })
        assertEquals(-85, gsm[0].value)
        assertNull(GaugeMath.readings(emptyList()).single().value)
    }

    @Test fun currentPrefersPrimaryServingThenRegisteredThenSignalReport() {
        val neighbour = row("LTE", false, 0, lte(-80))
        val serving = row("LTE", true, 1, lte(-100))
        assertEquals(-100, GaugeMath.current(batch(1, neighbour, serving), null)!!.primary!!.value)
        val registered = row("NR", true, 0, listOf(CellularMetric("NR SS-RSRP", -90, "dBm")))
        assertEquals("NR", GaugeMath.current(batch(1, neighbour, registered), null)!!.rat)
        val set = GaugeMath.current(batch(1, neighbour), report(2, "LTE", lte(-70)))!!
        assertEquals("Subscription signal report", set.source)
        assertEquals(-70, set.primary!!.value)
        assertNull(GaugeMath.current(null, null))
        assertNull(GaugeMath.current(batch(1, row("LTE", true, 1, lte(null, null, null))), null))
    }

    @Test fun seriesUsesSameSourceOldestFirstBounded() {
        val history = (10 downTo 1).map { batch(it * 1000L, row("LTE", true, 1, lte(-100 + it))) }
        val set = GaugeMath.current(history.first(), null)
        val series = GaugeMath.series(set, history, listOf(report(99_000, "LTE", lte(-50))), max = 5)
        assertEquals(listOf(-94, -93, -92, -91, -90), series.map { it.value })
        assertTrue(series.zipWithNext().all { it.first.atMillis < it.second.atMillis })
        val signalSet = GaugeMath.current(null, report(5, "LTE", lte(-60)))
        assertEquals(listOf(-61, -60), GaugeMath.series(signalSet, history,
            listOf(report(5, "LTE", lte(-60)), report(4, "LTE", lte(-61)), report(3, "NR", lte(-1)))).map { it.value })
        assertTrue(GaugeMath.series(null, history, emptyList()).isEmpty())
    }
}
