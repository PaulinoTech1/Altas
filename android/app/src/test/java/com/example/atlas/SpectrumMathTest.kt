package com.example.atlas

import com.example.atlas.acquisition.wifi.WifiConnection
import com.example.atlas.acquisition.wifi.WifiDelivery
import com.example.atlas.acquisition.wifi.WifiObservation
import com.example.atlas.ui.viz.SpectrumAp
import com.example.atlas.ui.viz.SpectrumMath
import com.example.atlas.ui.viz.SpectrumMath.Band
import com.example.atlas.ui.viz.VizMath
import org.junit.Assert.*
import org.junit.Test

class SpectrumMathTest {
    private fun obs(id: String, bssid: String?, rssi: Int?, freq: Int?, width: String = "20 MHz", center0: Int? = null, ssid: String? = "net") =
        WifiObservation(id, ssid, bssid, rssi, freq, null, width, center0, null, null, 0L, 0L, null, 0L, WifiDelivery.CACHE_READ)

    private fun ap(key: String, rssi: Int, center: Int, width: Int = 20) =
        SpectrumAp(key, key, key, center, center, width, rssi, null, false)

    @Test fun bandsAndWidths() {
        assertEquals(Band.GHZ_2_4, SpectrumMath.bandOf(2437))
        assertEquals(Band.GHZ_5, SpectrumMath.bandOf(5180))
        assertEquals(Band.GHZ_6, SpectrumMath.bandOf(5955))
        assertNull(SpectrumMath.bandOf(60480))
        assertEquals(80, SpectrumMath.widthMhz("80 MHz"))
        assertEquals(80, SpectrumMath.widthMhz("80+80 MHz"))
        assertEquals(320, SpectrumMath.widthMhz("320 MHz"))
        assertEquals(20, SpectrumMath.widthMhz("Unavailable / unsupported (7)"))
    }

    @Test fun channelTicksMatchWifiPlan() {
        assertEquals(2412, SpectrumMath.channelMhz(Band.GHZ_2_4, 1))
        assertEquals(2484, SpectrumMath.channelMhz(Band.GHZ_2_4, 14))
        assertEquals(5180, SpectrumMath.channelMhz(Band.GHZ_5, 36))
        assertEquals(5955, SpectrumMath.channelMhz(Band.GHZ_6, 1))
        SpectrumMath.Band.entries.forEach { band ->
            SpectrumMath.ticks(band).forEach { (_, mhz) -> assertTrue(SpectrumMath.x(mhz.toFloat(), band) in 0f..1f) }
        }
    }

    @Test fun centerUsesCenterFrequencyOnlyWhenPlausible() {
        assertEquals(5210, SpectrumMath.centerMhz(5180, 5210, 80))
        assertEquals(5180, SpectrumMath.centerMhz(5180, null, 80))
        assertEquals(5180, SpectrumMath.centerMhz(5180, 0, 80))
        assertEquals(5180, SpectrumMath.centerMhz(5180, 5210, 20))
        assertEquals(5180, SpectrumMath.centerMhz(5180, 2437, 80))
    }

    @Test fun heightAndProfile() {
        assertEquals(0f, SpectrumMath.height(-100), 0f)
        assertEquals(1f, SpectrumMath.height(-30), 0f)
        assertEquals(0.5f, SpectrumMath.height(-65), 1e-6f)
        assertEquals(1f, SpectrumMath.height(-10), 0f)
        assertEquals(1f, SpectrumMath.profile(0f), 0f)
        assertEquals(0f, SpectrumMath.profile(1f), 0f)
        assertEquals(0f, SpectrumMath.profile(-1.5f), 0f)
        assertTrue(SpectrumMath.profile(0.5f) in 0.1f..0.9f)
    }

    @Test fun hitPicksTallestHumpUnderTap() {
        val weak = ap("weak", -80, 5180, 80)
        val strong = ap("strong", -40, 5200, 20)
        val x = SpectrumMath.x(5200f, Band.GHZ_5)
        assertEquals("strong", SpectrumMath.hit(x, listOf(weak, strong), Band.GHZ_5)?.key)
        assertEquals("weak", SpectrumMath.hit(SpectrumMath.x(5160f, Band.GHZ_5), listOf(weak, strong), Band.GHZ_5)?.key)
        assertNull(SpectrumMath.hit(SpectrumMath.x(5800f, Band.GHZ_5), listOf(weak, strong), Band.GHZ_5))
        assertNull(SpectrumMath.hit(x, listOf(weak, strong), Band.GHZ_2_4))
    }

    @Test fun fromRowsSkipsUndrawableAndMarksConnected() {
        val rows = listOf(
            obs("1", "aa", -50, 5180, "80 MHz", 5210, ssid = null),
            obs("2", "aa", -60, 5180),
            obs("3", "bb", null, 2437),
            obs("4", null, -70, 2437),
            obs("5", "cc", -70, null)
        )
        val conn = listOf(WifiConnection("k", "net", "aa", -50, 5180, 36, null, null, null, null, true, 0L))
        val aps = SpectrumMath.fromRows(rows, conn)
        assertEquals(listOf("aa", "4"), aps.map { it.key })
        assertTrue(aps[0].connected)
        assertEquals(5210, aps[0].centerMhz)
        assertEquals(80, aps[0].widthMhz)
        assertEquals("SSID hidden / unavailable", aps[0].label)
        assertEquals(36, aps[0].channel)
        assertFalse(aps[1].connected)
    }

    @Test fun summaryAndDefaultBand() {
        val aps = listOf(ap("a", -42, 5180).copy(channel = 36), ap("b", -70, 5200), ap("c", -60, 2437))
        assertEquals("Wi-Fi spectrum, 5 GHz, 2 networks, strongest -42 dBm on channel 36", SpectrumMath.summary(aps, Band.GHZ_5))
        assertEquals("Wi-Fi spectrum, 6 GHz, no networks reported in this band", SpectrumMath.summary(aps, Band.GHZ_6))
        assertEquals(Band.GHZ_5, SpectrumMath.defaultBand(aps))
        assertEquals(Band.GHZ_2_4, SpectrumMath.defaultBand(emptyList()))
        assertEquals(Band.GHZ_2_4, SpectrumMath.defaultBand(listOf(ap("c", -60, 2437))))
    }

    @Test fun retargetTweensFromCurrentAndFadesDropped() {
        val first = SpectrumMath.retarget(emptyList(), 1f, listOf(ap("a", -65, 5180)))
        assertEquals(0f, first.single().height(0f), 0f)
        assertEquals(0.5f, first.single().height(1f), 1e-6f)
        // Halfway through a tween, a new scan drops "a" and adds "b": "a" fades from its current shape.
        val second = SpectrumMath.retarget(first, 0.5f, listOf(ap("b", -30, 5200)))
        val a = second.first { it.ap.key == "a" }
        assertEquals(0.25f, a.height(0f), 1e-6f)
        assertEquals(0.25f, a.height(1f), 1e-6f)
        assertEquals(0.5f, a.alpha(0f), 1e-6f)
        assertEquals(0f, a.alpha(1f), 0f)
        // Once faded, it is dropped.
        assertTrue(SpectrumMath.retarget(second, 1f, listOf(ap("b", -30, 5200))).none { it.ap.key == "a" })
    }

    @Test fun stableColorsAndHues() {
        assertEquals(VizMath.stableHue("aa:bb"), VizMath.stableHue("aa:bb"), 0f)
        assertNotEquals(VizMath.stableHue("aa:bb:cc:dd:ee:01"), VizMath.stableHue("aa:bb:cc:dd:ee:02"), 0f)
        assertTrue(VizMath.stableHue("x") in 0f..360f)
        assertEquals(0xff0000, VizMath.hslToRgb(0f, 1f, 0.5f))
        assertEquals(0x00ff00, VizMath.hslToRgb(120f, 1f, 0.5f))
        assertEquals(0x0000ff, VizMath.hslToRgb(240f, 1f, 0.5f))
        assertEquals(0x808080, VizMath.hslToRgb(42f, 0f, 0.5f))
    }
}
