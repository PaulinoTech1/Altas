package com.example.atlas

import com.example.atlas.acquisition.bluetooth.Advertisement
import com.example.atlas.acquisition.bluetooth.BleDeviceHistory
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.acquisition.bluetooth.FlipperSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipperSignatureTest {
    private fun device(name: String?, services: List<String> = emptyList(), count: Long = 1) = BleDeviceHistory(
        BleObservation("e", "AA:BB:CC:DD:EE:FF", 1L, 1L, null, -60, true,
            Advertisement(name = name, serviceUuids = services)), 1L, count)

    @Test fun genericHidAndCoincidentalNamesAreNotFlipperHints() {
        assertNull(FlipperSignature.assess(device("Flipperfish", listOf("00001812-0000-1000-8000-00805f9b34fb"))))
        assertNull(FlipperSignature.assess(device("Control", listOf("00001812-0000-1000-8000-00805f9b34fb"))))
    }

    @Test fun singleAdvertisedIndicatorIsOnlyPossible() {
        assertEquals("Possible Flipper Zero signal", FlipperSignature.assess(device("Flipper Bob"))?.label)
        assertEquals("Possible Flipper Zero signal", FlipperSignature.assess(device(null, listOf(FlipperSignature.SERIAL_SERVICE)))?.label)
    }

    @Test fun combinedIndicatorsAndRepeatedObservationsIncreaseHintOnly() {
        val result = FlipperSignature.assess(device("Flipper Bob", listOf(FlipperSignature.SERIAL_SERVICE), 3))
        assertEquals("Heightened Flipper Zero possibility", result?.label)
        assertTrue(result!!.evidence.size == 3)
        assertEquals("Possible Flipper Zero signal", FlipperSignature.assess(device("Flipper Bob", listOf(FlipperSignature.SERIAL_SERVICE)))?.label)
    }
}
