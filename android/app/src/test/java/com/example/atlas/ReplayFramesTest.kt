package com.example.atlas

import com.example.atlas.acquisition.bluetooth.Advertisement
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.acquisition.cellular.*
import com.example.atlas.acquisition.wifi.WifiConnection
import com.example.atlas.acquisition.wifi.WifiDelivery
import com.example.atlas.acquisition.wifi.WifiObservation
import com.example.atlas.replay.ReplayFrames
import com.example.atlas.replay.ReplayTimeline
import com.example.atlas.replay.ReplayViewModel
import com.example.atlas.telemetry.TelemetryNormalizer
import com.example.atlas.ui.viz.GaugeMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayFramesTest {
    private val boot = "boot"
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun wifi(at: Long, bssid: String, rssi: Int, freq: Int, ssid: String? = "home") = TelemetryNormalizer.wifi(
        WifiObservation("w$at$bssid", ssid, bssid, rssi, freq, 36, "80 MHz", 5210, null, "[WPA2]", at, at * 1000, at * 1000, 0L, WifiDelivery.CACHE_READ), boot)
    private fun ble(at: Long, address: String, rssi: Int?) = listOf(TelemetryNormalizer.ble(
        BleObservation("b$at$address", address, at, at * 1_000_000, null, rssi, true, Advertisement(name = "Tag")), boot))

    @Test fun rebuildsWifiFromSavedRowPairsWithSavedLabels() {
        val rows = wifi(10_000, "aa", -50, 5180) + wifi(10_000, "bb", -70, 2437, ssid = null) +
            TelemetryNormalizer.connection(WifiConnection("c", "home", "aa", -50, 5180, 36, 100, null, null, null, true, 10_500), boot)
        val timeline = ReplayFrames.build(rows.shuffled())
        assertTrue(timeline.hasWifi); assertFalse(timeline.hasBle); assertFalse(timeline.hasCellular)
        val aps = timeline.frames.single().wifi.associateBy { it.key }
        assertEquals(-50, aps.getValue("aa").rssiDbm)
        assertEquals(5180, aps.getValue("aa").centerMhz) // center frequency is not saved
        assertEquals(80, aps.getValue("aa").widthMhz)
        assertTrue(aps.getValue("aa").connected)
        assertEquals("SSID hidden / unavailable", aps.getValue("bb").label)
        assertFalse(aps.getValue("bb").connected)
    }

    @Test fun framesAreWindowedOldestFirstCarryRecentAndBounded() {
        val rows = (0 until 200).flatMap { ble(it * 5_000L, "dev", -60 - (it % 10)) } + ble(3_000, "gone", -40) + ble(1_000, "x", null)
        val timeline = ReplayFrames.build(rows)
        assertEquals(ReplayFrames.MAX_FRAMES, timeline.frames.size)
        assertTrue(timeline.frames.zipWithNext().all { it.first.atMillis < it.second.atMillis })
        val last = timeline.frames.last()
        assertEquals(listOf("dev"), last.ble.map { it.address })
        assertEquals(200L, last.ble.single().count)
        assertEquals("Tag", last.ble.single().label)
    }

    @Test fun cellsAndSignalRebuildIntoGaugeInputs() {
        val batch = CellBatch("e", listOf(
            CellRecord("LTE", true, 1, 42L, 7, 1, 100, "001", "01", listOf(CellularMetric("LTE RSRP", -95, "dBm"), CellularMetric("LTE RSRQ", -9, "dB")), null),
            CellRecord("LTE", false, 0, 43L, 8, 1, 100, "001", "01", listOf(CellularMetric("LTE RSRP", -110, "dBm")), null)
        ), 2, 1, CellDelivery.CALLBACK, 20_000, 0, 0, "api")
        val signal = SignalReport(listOf(SubscriptionSignal("NR", listOf(CellularMetric("NR SS-RSRP", -88, "dBm")))), 21_000, 0, null)
        val timeline = ReplayFrames.build(TelemetryNormalizer.cells(batch, boot) + TelemetryNormalizer.signal(signal, boot))
        val frame = timeline.frames.last()
        val set = GaugeMath.current(frame.cells, frame.signal)!!
        assertEquals("Serving cell", set.source)
        assertEquals(-95, set.primary!!.value)
        assertEquals(-9, set.readings[1].value)
        assertNull(set.readings[2].value)
        assertEquals("NR", frame.signal!!.signals.single().rat)
        assertEquals(2, frame.cells!!.rows.size)
    }

    @Test fun emptyOrDiagnosticOnlyDataYieldsEmptyTimeline() {
        assertTrue(ReplayFrames.build(emptyList()).isEmpty)
        assertTrue(ReplayFrames.build(ble(1, "x", null)).isEmpty)
        val frames = ReplayFrames.build((0 until 3).flatMap { ble(it * 10_000L, "a", -50) }).frames
        assertEquals(listOf(frames[1], frames[0]), ReplayFrames.historyAt(frames, 1))
    }

    @Test fun viewModelLoopsLoadsOnlyWhenEnabledAndResetsOnOff() = runTest(dispatcher) {
        var loads = 0
        val timeline = ReplayFrames.build((0 until 3).flatMap { ble(it * 10_000L, "a", -50) })
        val vm = ReplayViewModel { loads++; timeline }
        assertFalse(vm.state.value.enabled)
        runCurrent(); assertEquals(0, loads)
        vm.setEnabled(true)
        assertTrue(vm.state.value.loading)
        runCurrent()
        assertEquals(1, loads)
        assertEquals(0, vm.state.value.index)
        advanceTimeBy(ReplayViewModel.FRAME_MILLIS + 1); assertEquals(1, vm.state.value.index)
        advanceTimeBy(2 * ReplayViewModel.FRAME_MILLIS); assertEquals(0, vm.state.value.index) // loops
        vm.setEnabled(false)
        assertEquals(com.example.atlas.replay.ReplayState(), vm.state.value)
        advanceTimeBy(5_000); assertEquals(0, vm.state.value.index)
    }

    @Test fun viewModelShowsEmptyStateWithoutInventingFrames() = runTest(dispatcher) {
        val vm = ReplayViewModel { ReplayTimeline(emptyList(), false, false, false) }
        vm.setEnabled(true); runCurrent()
        assertTrue(vm.state.value.enabled)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.frame)
        vm.setEnabled(false)
    }
}
