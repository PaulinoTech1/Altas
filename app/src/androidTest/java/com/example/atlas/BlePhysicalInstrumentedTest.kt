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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.atlas.acquisition.bluetooth.BluetoothViewModel
import com.example.atlas.permissions.RadioPermissionManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical acquisition; never makes a GATT connection. */
@RunWith(AndroidJUnit4::class)
class BlePhysicalInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realAdvertisementsAndForegroundCleanup() {
        assumeTrue("Requires explicit atlas.bleHardware=true", InstrumentationRegistry.getArguments().getString("atlas.bleHardware") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = RadioPermissionManager(app)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val vm = ViewModelProvider(compose.activity)[BluetoothViewModel::class.java]
        assertFalse("No acquisition at launch", vm.state.value.scanning)
        assertTrue("No synthetic measurements at launch", vm.state.value.history.isEmpty())
        assertTrue("Location must already be enabled; test never changes device settings",
            app.getSystemService(LocationManager::class.java).isLocationEnabled)
        click("Open Bluetooth")
        click("Review BLE permissions")
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (permissions.missingForBleScan().isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            device.findObject(By.res("com.android.permissioncontroller", "permission_location_accuracy_radio_fine"))?.click()
            val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 1000)
                ?: device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")), 1000)
            allow?.click()
        }
        assertTrue("Grant failed: ${permissions.missingForBleScan()}; no shell permission grants used", permissions.missingForBleScan().isEmpty())
        compose.waitForIdle()
        assertFalse("Permission grant alone must not scan", vm.state.value.scanning)

        click("Start BLE scan")
        compose.waitUntil(2000) { vm.state.value.scanning || vm.state.value.error != null }
        assertTrue("${vm.state.value.error}", vm.state.value.scanning)
        compose.waitUntil(15_000) { !vm.state.value.scanning }
        assertNull(vm.state.value.error)
        assertEquals("Scan time limit reached", vm.state.value.status)
        val first = vm.state.value
        assertTrue("No peripheral advertisements received; real discovery NOT VERIFIED", first.history.isNotEmpty())
        assertTrue(first.history.any { it.rssiDbm != null })
        assertTrue(first.history.any { it.sourceElapsedNanos != null && it.advertisement.rawHex.isNotEmpty() })
        assertTrue(first.devices.size <= 256 && first.history.size <= 1024)
        assertNull("Discovery must never auto-connect", vm.gatt.state.value.address)
        Log.i("AtlasBleValidation", "timed_scan: devices=${first.devices.size}, received=${first.receivedCount}, retained=${first.history.size}, rssi_min=${first.history.mapNotNull { it.rssiDbm }.minOrNull()}, rssi_max=${first.history.mapNotNull { it.rssiDbm }.maxOrNull()}, source_timestamped=${first.history.count { it.sourceElapsedNanos != null }}, malformed=${first.history.count { it.advertisement.malformed }}; no GATT connection")

        click("30s")
        click("Start BLE scan")
        compose.waitUntil(2000) { vm.state.value.scanning || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        click("Stop BLE scan")
        compose.waitForIdle()
        assertFalse(vm.state.value.scanning)
        val afterStop = vm.state.value.receivedCount
        SystemClock.sleep(1500)
        assertEquals("Observations continued after explicit stop", afterStop, vm.state.value.receivedCount)
        Log.i("AtlasBleValidation", "manual_stop: no observations added over 1500ms")

        // A new immediate start must fail visibly, not work around Android scan policies.
        click("Start BLE scan")
        compose.waitUntil(2000) { vm.state.value.error != null }
        assertTrue(vm.state.value.error.orEmpty().contains("cooldown"))
        Log.i("AtlasBleValidation", "cooldown: immediate restart rejected visibly")

        SystemClock.sleep(10_100)
        click("Start BLE scan")
        compose.waitUntil(2000) { vm.state.value.scanning || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        click("Back to dashboard")
        compose.waitForIdle()
        assertFalse("Leaving Bluetooth must stop scan", vm.state.value.scanning)
        val afterNavigation = vm.state.value.receivedCount
        SystemClock.sleep(1500)
        assertEquals(afterNavigation, vm.state.value.receivedCount)
        Log.i("AtlasBleValidation", "navigation_stop: scan stopped; no observations added over 1500ms")

        SystemClock.sleep(10_100)
        click("Open Bluetooth")
        click("Start BLE scan")
        compose.waitUntil(2000) { vm.state.value.scanning || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse("Backgrounding must stop scan", vm.state.value.scanning)
        val afterBackground = vm.state.value.receivedCount
        SystemClock.sleep(1500)
        assertEquals(afterBackground, vm.state.value.receivedCount)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertFalse("Resume must not auto-start", vm.state.value.scanning)
        assertNull(vm.gatt.state.value.address)
        Log.i("AtlasBleValidation", "background_stop: no observations added over 1500ms; resume stayed idle; GATT not connected")
    }

    private fun click(text: String) {
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performClick()
    }
}
