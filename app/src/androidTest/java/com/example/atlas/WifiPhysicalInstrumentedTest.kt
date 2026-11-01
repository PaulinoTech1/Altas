package com.example.atlas

import android.location.LocationManager
import android.net.wifi.ScanResult
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.atlas.acquisition.wifi.WifiFreshness
import com.example.atlas.acquisition.wifi.WifiRequestStatus
import com.example.atlas.acquisition.wifi.WifiViewModel
import com.example.atlas.permissions.RadioPermissionManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in local Wi-Fi acquisition. Never changes association, location services or throttling. */
@RunWith(AndroidJUnit4::class)
class WifiPhysicalInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realSurveyConnectionAndCleanup() {
        assumeTrue("Requires explicit atlas.wifiHardware=true", InstrumentationRegistry.getArguments().getString("atlas.wifiHardware") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = RadioPermissionManager(app)
        val vm = ViewModelProvider(compose.activity)[WifiViewModel::class.java]
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertFalse(vm.state.value.collecting)
        assertTrue(vm.state.value.rows.isEmpty())
        assertTrue("Test requires already-enabled location; it never changes settings", app.getSystemService(LocationManager::class.java).isLocationEnabled)
        click("Open Wi-Fi")
        click("Review Wi-Fi permissions")
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (permissions.missingForWifiSurvey().isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            device.findObject(By.res("com.android.permissioncontroller", "permission_location_accuracy_radio_fine"))?.click()
            device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")), 1000)?.click()
        }
        assertTrue("Location grant failed; no shell grants used", permissions.missingForWifiSurvey().isEmpty())
        compose.waitForIdle()
        assertFalse("Permission grant alone must not collect", vm.state.value.collecting)
        click("Start Wi-Fi survey")
        compose.waitUntil(5000) { vm.state.value.request != null || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertEquals("${vm.state.value.request}", WifiRequestStatus.ACCEPTED, vm.state.value.request?.status)
        compose.waitUntil(30_000) { vm.state.value.updatedBroadcasts > 0 || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        val now = SystemClock.elapsedRealtimeNanos() / 1000
        val snapshot = vm.state.value
        assertTrue("No real AP measurements: discovery NOT VERIFIED", snapshot.rows.isNotEmpty())
        assertTrue(snapshot.rows.any { it.rssiDbm != null && it.frequencyMhz != null && it.sourceElapsedMicros != null })
        assertTrue("No recent source-timestamped update", snapshot.rows.any { it.freshness(now) == WifiFreshness.RECENT })
        assertTrue(snapshot.rows.any { it.ssid != null && it.bssid != null })
        snapshot.rows.filter { it.channel != null }.forEach {
            assertEquals(ScanResult.convertFrequencyMhzToChannelIfSupported(it.frequencyMhz!!), it.channel)
        }
        compose.waitUntil(10_000) { vm.state.value.connections.isNotEmpty() }
        assertTrue("Connected Wi-Fi RSSI unavailable", vm.state.value.connections.any { it.rssiDbm != null && it.frequencyMhz != null })
        Log.i("AtlasWifiValidation", "survey: rows=${snapshot.rows.size}, source_samples=${snapshot.history.size}, updated_broadcasts=${snapshot.updatedBroadcasts}, recent_rows=${snapshot.rows.count { it.freshness(now) == WifiFreshness.RECENT }}, rssi_min=${snapshot.rows.mapNotNull { it.rssiDbm }.minOrNull()}, rssi_max=${snapshot.rows.mapNotNull { it.rssiDbm }.maxOrNull()}, bands=${snapshot.rows.mapNotNull { it.frequencyMhz }.map { com.example.atlas.acquisition.wifi.WifiValues.band(it) }.distinct()}, connected_snapshots=${vm.state.value.connections.size}; no identifiers logged")

        click("Request Wi-Fi scan")
        compose.waitUntil(5000) { vm.state.value.request?.status == WifiRequestStatus.LOCAL_RATE_LIMIT }
        Log.i("AtlasWifiValidation", "rate_guard: immediate request rejected locally; os_throttling=${vm.state.value.osScanThrottlingEnabled}; Android throttle exhaustion not forced")

        // A second explicit scan respects spacing and establishes real time-series samples.
        SystemClock.sleep(30_100)
        val beforeSecond = vm.state.value.updatedBroadcasts
        click("Request Wi-Fi scan")
        compose.waitUntil(5000) { vm.state.value.request?.status == WifiRequestStatus.ACCEPTED || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertEquals(WifiRequestStatus.ACCEPTED, vm.state.value.request?.status)
        compose.waitUntil(20_000) { vm.state.value.updatedBroadcasts > beforeSecond || !vm.state.value.collecting }
        assertTrue("No second platform update arrived", vm.state.value.updatedBroadcasts > beforeSecond)
        val repeated = vm.state.value.history.filter { it.bssid != null && it.rssiDbm != null }.groupBy { it.bssid }.filterValues { it.size >= 2 }
        assertTrue("No AP has multiple real source samples; history NOT VERIFIED", repeated.isNotEmpty())
        compose.runOnIdle { vm.select(repeated.keys.first()) }
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Selected AP signal history"))
        compose.onNodeWithText("Selected AP signal history").assertIsDisplayed()
        Log.i("AtlasWifiValidation", "history: source_samples=${vm.state.value.history.size}, addresses_with_multiple_samples=${repeated.size}, updated_broadcasts=${vm.state.value.updatedBroadcasts}; selected chart rendered; no identifiers logged")
        click("Stop Wi-Fi survey")
        compose.waitForIdle()
        assertFalse(vm.state.value.collecting)
        val afterStop = vm.state.value.scanBroadcasts
        val connectionsAfterStop = vm.state.value.connections
        SystemClock.sleep(1500)
        assertEquals(afterStop, vm.state.value.scanBroadcasts)
        assertEquals(connectionsAfterStop, vm.state.value.connections)
        Log.i("AtlasWifiValidation", "manual_stop: callbacks unchanged over 1500ms; OS scan cancellation not claimed")

        // A second session may read cache/listen, but the guard prevents another hardware request.
        click("Start Wi-Fi survey")
        compose.waitUntil(3000) { vm.state.value.collecting }
        click("Back to dashboard")
        compose.waitForIdle()
        assertFalse("Navigation must stop collection", vm.state.value.collecting)
        val afterNavigation = vm.state.value.scanBroadcasts
        val connectionsAfterNavigation = vm.state.value.connections
        SystemClock.sleep(1500)
        assertEquals(afterNavigation, vm.state.value.scanBroadcasts)
        assertEquals(connectionsAfterNavigation, vm.state.value.connections)
        Log.i("AtlasWifiValidation", "navigation_stop: callbacks unchanged over 1500ms")

        click("Open Wi-Fi")
        click("Start Wi-Fi survey")
        compose.waitUntil(3000) { vm.state.value.collecting }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse("Background must stop collection", vm.state.value.collecting)
        val afterBackground = vm.state.value.scanBroadcasts
        val connectionsAfterBackground = vm.state.value.connections
        SystemClock.sleep(1500)
        assertEquals(afterBackground, vm.state.value.scanBroadcasts)
        assertEquals(connectionsAfterBackground, vm.state.value.connections)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertFalse("Resume must remain idle", vm.state.value.collecting)
        Log.i("AtlasWifiValidation", "background_stop: callbacks unchanged over 1500ms; resume stayed idle")
    }

    private fun click(text: String) {
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performClick()
    }
}
