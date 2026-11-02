package com.example.atlas

import com.example.atlas.acquisition.bluetooth.Advertisement
import com.example.atlas.acquisition.bluetooth.BleDeviceHistory
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.ui.viz.RadarDot
import com.example.atlas.ui.viz.RadarMath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class RadarMathTest {
    private fun obs(id: Int, address: String, rssi: Int?, name: String? = null) =
        BleObservation("e$id", address, id.toLong(), 0L, null, rssi, false, Advertisement(name = name))

    @Test fun strongerIsCloserAndClamped() {
        assertEquals(0f, RadarMath.radius(-30), 0f)
        assertEquals(0f, RadarMath.radius(-10), 0f)
        assertEquals(1f, RadarMath.radius(-100), 0f)
        assertEquals(1f, RadarMath.radius(-120), 0f)
        assertTrue(RadarMath.radius(-50) < RadarMath.radius(-80))
        assertEquals(RadarMath.RINGS.last(), RadarMath.FAR_DBM)
    }

    @Test fun angleIsStablePerAddress() {
        assertEquals(RadarMath.angle("AA:BB:CC:DD:EE:01"), RadarMath.angle("AA:BB:CC:DD:EE:01"), 0f)
        assertNotEquals(RadarMath.angle("AA:BB:CC:DD:EE:01"), RadarMath.angle("AA:BB:CC:DD:EE:02"), 0f)
        assertTrue(RadarMath.angle("x") in 0f..(2 * Math.PI).toFloat())
    }

    @Test fun dotSizeGrowsLogarithmicallyAndCaps() {
        assertEquals(3f, RadarMath.dotRadiusDp(0), 1e-6f)
        assertTrue(RadarMath.dotRadiusDp(10) < RadarMath.dotRadiusDp(100))
        assertEquals(12f, RadarMath.dotRadiusDp(Long.MAX_VALUE), 0f)
        assertEquals(3f, RadarMath.dotRadiusDp(-5), 1e-6f)
    }

    @Test fun dotsSkipMissingRssiAndUseListLabel() {
        val devices = listOf(
            BleDeviceHistory(obs(1, "A", -30, "Tag"), 0L, 4),
            BleDeviceHistory(obs(2, "B", null), 0L, 1),
            BleDeviceHistory(obs(3, "C", -100, " "), 0L, 1)
        )
        val dots = RadarMath.dots(devices)
        assertEquals(listOf("A", "C"), dots.map { it.address })
        assertEquals("Tag", dots[0].label)
        assertEquals("Unnamed BLE device", dots[1].label)
        assertEquals(0f, sqrt(dots[0].x * dots[0].x + dots[0].y * dots[0].y), 1e-6f)
        assertEquals(1f, sqrt(dots[1].x * dots[1].x + dots[1].y * dots[1].y), 1e-5f)
    }

    @Test fun hitPicksNearestWithinSlop() {
        val dots = listOf(RadarDot("a", "a", -50, 1, 0.5f, 0f), RadarDot("b", "b", -50, 1, 0.6f, 0f))
        assertEquals("b", RadarMath.hit(0.58f, 0f, dots, 0.1f)?.address)
        assertEquals("a", RadarMath.hit(0.5f, 0.05f, dots, 0.1f)?.address)
        assertNull(RadarMath.hit(-0.5f, 0f, dots, 0.1f))
    }

    @Test fun sparklinesAreBoundedOldestFirst() {
        // History is newest first, like BluetoothUiState.history.
        val history = (100 downTo 1).map { obs(it, if (it % 2 == 0) "A" else "B", -it) } + obs(0, "C", null)
        val lines = RadarMath.sparklines(history, points = 30)
        assertEquals(30, lines.getValue("A").size)
        assertEquals(-42, lines.getValue("A").first())
        assertEquals(-100, lines.getValue("A").last())
        assertNull(lines["C"])
    }

    @Test fun summaryMentionsStrengthNotDistance() {
        assertEquals("Bluetooth radar, no devices with a signal reading", RadarMath.summary(emptyList(), false))
        val text = RadarMath.summary(listOf(RadarDot("a", "a", -48, 1, 0f, 0f), RadarDot("b", "b", -70, 1, 0f, 0f)), true)
        assertEquals("Bluetooth radar, 2 devices, strongest -48 dBm, scanning. Rings show signal strength, not distance.", text)
    }
}
