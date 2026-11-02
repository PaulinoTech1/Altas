package com.example.atlas

import com.example.atlas.acquisition.bluetooth.Advertisement
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.ui.viz.BleLocatorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleLocatorMathTest {
    private fun sample(id: Int, address: String, dbm: Int?, elapsed: Long, session: String = "scan") =
        BleObservation("e$id", address, elapsed / 1_000_000, elapsed, elapsed, dbm, false, Advertisement(),
            orientationSessionId = session)

    @Test fun selectedAddressMustHaveFreshCurrentScanMeasurement() {
        val now = 20_000_000_000L
        val old = sample(0, "A", -45, 5_000_000_000L, "old")
        assertEquals(BleLocatorMath.Status.SELECT_TARGET, BleLocatorMath.evaluate(listOf(old), null, true, 19_000, now).status)
        assertEquals(BleLocatorMath.Status.WAITING, BleLocatorMath.evaluate(listOf(old), "A", true, 19_000, now).status)
        assertEquals(BleLocatorMath.Status.ENDED, BleLocatorMath.evaluate(listOf(old), "A", false, null, now).status)
        assertEquals(BleLocatorMath.Status.NO_SIGNAL, BleLocatorMath.evaluate(listOf(old), "B", false, null, now).status)
    }

    @Test fun staleAndMissingRssiDoNotBecomeLiveGuidance() {
        val now = 20_000_000_000L
        val stale = sample(1, "A", -70, 16_000_000_000L)
        assertEquals(BleLocatorMath.Status.STALE, BleLocatorMath.evaluate(listOf(stale), "A", true, 15_000, now).status)
        assertEquals(BleLocatorMath.Status.WAITING,
            BleLocatorMath.evaluate(listOf(sample(2, "A", null, 19_000_000_000L)), "A", true, 15_000, now).status)
    }

    @Test fun trendUsesMedianOfSixRecentSamplesFromOneSession() {
        val now = 20_000_000_000L
        val values = listOf(-50, -51, -49, -64, -65, -63)
        val history = values.mapIndexed { index, dbm -> sample(index, "A", dbm, now - index * 1_000_000_000L) } +
            sample(7, "A", -10, now - 6_000_000_000L, "old")
        val reading = BleLocatorMath.evaluate(history, "A", true, 10_000, now)
        assertEquals(BleLocatorMath.Status.LIVE, reading.status)
        assertEquals(-50, reading.latestDbm)
        assertEquals(14, reading.trendDb)
        assertEquals(-49, reading.bestDbm)
        assertEquals(6, reading.sampleCount)
        assertNull(BleLocatorMath.evaluate(history.take(5), "A", true, 10_000, now).trendDb)
    }
}
