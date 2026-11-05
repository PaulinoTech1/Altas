package com.example.atlas

import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.atlas.acquisition.bluetooth.BleScanConfig
import com.example.atlas.acquisition.bluetooth.BleScanMode
import com.example.atlas.acquisition.bluetooth.BluetoothViewModel
import com.example.atlas.acquisition.bluetooth.BleSignalAnalysis
import com.example.atlas.permissions.RadioPermissionManager
import com.example.atlas.ui.viz.BleLocatorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical signal search; observes one advertiser and never connects to it. */
@RunWith(AndroidJUnit4::class)
class BleLocatorPhysicalInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun foregroundSearchTracksRealRssiAndStops() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlas.locatorHardware") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(RadioPermissionManager(app).missingForBleScan().isEmpty())
        assumeTrue(app.getSystemService(LocationManager::class.java).isLocationEnabled)
        val vm = ViewModelProvider(compose.activity)[BluetoothViewModel::class.java]
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Open Bluetooth"))
        compose.onNodeWithText("Open Bluetooth").performClick()
        vm.configure(BleScanConfig(BleScanMode.LOW_LATENCY, 20))
        try {
            vm.start()
            compose.waitUntil(23_000) { vm.state.value.history.any { it.rssiDbm != null } || vm.state.value.error != null }
            assertEquals(null, vm.state.value.error)
            val liveState = vm.state.value
            val event = liveState.history.firstOrNull { it.rssiDbm != null }
            assertNotNull("No real BLE RSSI during foreground search", event)
            assertNotNull("Scan callback sequence missing", event!!.scanSequence)
            val live = BleLocatorMath.evaluate(liveState.history, event!!.address, true,
                liveState.sessionStartedAtMillis, SystemClock.elapsedRealtimeNanos())
            assertEquals(BleLocatorMath.Status.LIVE, live.status)
            assertEquals(event.rssiDbm, live.latestDbm)
            vm.stop("Physical locator test stopped")
            compose.waitForIdle()
            assertFalse(vm.state.value.scanning)
            val ended = BleLocatorMath.evaluate(vm.state.value.history, event.address, false,
                null, SystemClock.elapsedRealtimeNanos())
            assertEquals(BleLocatorMath.Status.ENDED, ended.status)
            val evidence = BleSignalAnalysis.summarize(vm.state.value.history, event.address)
            Log.i("AtlasLocatorValidation", "session_samples=${evidence.sessionSamples}, target_samples=${evidence.targetSamples}, " +
                "paired=${evidence.pairedSamples}, missing_orientation=${evidence.missingOrientation}, " +
                "retained_sequence_gaps_at_least=${evidence.sequenceGapsAtLeast}; no device identifier logged")
            Log.i("AtlasLocatorValidation", "live_then_stopped: rssi_dbm=${live.latestDbm}, samples=${live.sampleCount}; no device identifier logged")
        } finally {
            vm.stop("Physical locator test cleanup")
        }
    }
}
