package com.example.atlas

import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
import com.example.atlas.acquisition.cellular.*
import com.example.atlas.permissions.RadioPermissionManager
import com.example.atlas.telemetry.TelemetryViewModel
import com.example.atlas.telemetry.TelemetryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CellularPhysicalInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realCellularReportsAndCleanup() {
        assumeTrue("Requires explicit atlas.cellularHardware=true", InstrumentationRegistry.getArguments().getString("atlas.cellularHardware") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = RadioPermissionManager(app)
        val vm = ViewModelProvider(compose.activity)[CellularViewModel::class.java]
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val requireCellularDefault = InstrumentationRegistry.getArguments().getString("atlas.requireCellularDefault") == "true"
        if (requireCellularDefault) verifyCellularDefault("before_collection")
        assertFalse(vm.state.value.collecting)
        assertNull(vm.state.value.cells)
        assertNull(vm.state.value.signal)
        assertTrue("Location must already be enabled; test does not change settings", app.getSystemService(LocationManager::class.java).isLocationEnabled)
        click("Open Cellular")
        click("Review cellular permissions")
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (permissions.missingForCellular().isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            device.findObject(By.res("com.android.permissioncontroller", "permission_location_accuracy_radio_fine"))?.click()
            val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 1000)
                ?: device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button")), 1000)
            allow?.click()
        }
        assertTrue("Normal permission grant failed; no shell grants used", permissions.missingForCellular().isEmpty())
        compose.waitForIdle()
        assertFalse("Permission grant must not collect", vm.state.value.collecting)
        click("Start cellular collection")
        compose.waitUntil(30_000) {
            val state = vm.state.value
            state.error != null || (state.signal != null && state.service != null && state.display != null &&
                state.cells != null && state.request != null && state.request?.status != CellRequestStatus.PENDING)
        }
        val snapshot = vm.state.value
        if (requireCellularDefault) verifyCellularDefault("during_collection")
        assertNull(snapshot.error)
        assertNotNull("No active subscription; per-subscription path NOT VERIFIED", snapshot.selectedId)
        val rows = snapshot.cells?.rows.orEmpty()
        val signals = snapshot.signal?.signals.orEmpty()
        val metrics = rows.flatMap { it.metrics } + signals.flatMap { it.metrics }
        assertTrue("LTE/NR values not supplied; metrics NOT VERIFIED", metrics.any { it.value != null && (it.name.startsWith("LTE") || it.name.startsWith("NR")) })
        assertTrue("No cell list supplied; cell identities NOT VERIFIED", rows.isNotEmpty())
        val lte = metrics.filter { it.name.startsWith("LTE") && it.value != null }
        val nr = metrics.filter { it.name.startsWith("NR") && it.value != null }
        Log.i("AtlasCellValidation", "cell_fields: identifiers_available=${rows.count { it.cellId != null }}, pci_available=${rows.count { it.pci != null }}, source_timestamps_available=${rows.count { it.sourceElapsedMillis != null }}, primary_roles=${rows.count { it.connectionStatus == 1 }}, secondary_roles=${rows.count { it.connectionStatus == 2 }}; no identifier values logged")
        Log.i("AtlasCellValidation", "reports: active_slots=${snapshot.subscriptions.size}, base_type=${snapshot.display?.technology}, carrier_hint=${snapshot.display?.carrierHint}, voice_state=${snapshot.service?.voiceState}, registration_rows=${snapshot.service?.registrations?.size}, cells=${rows.size}, registered_cells=${rows.count { it.registered }}, reported_nonregistered_cells=${rows.count { !it.registered }}, cell_delivery=${snapshot.cells?.delivery}, request_status=${snapshot.request?.status}, lte_available=${lte.size}, nr_available=${nr.size}; no identifiers logged")
        listOf("LTE RSRP", "LTE RSRQ", "LTE RSSNR", "NR SS-RSRP", "NR SS-RSRQ", "NR SS-SINR").forEach { name ->
            val values = metrics.filter { it.name == name }.mapNotNull { it.value }
            Log.i("AtlasCellValidation", "$name: available_values=${values.size}, min=${values.minOrNull()}, max=${values.maxOrNull()}")
        }
        if (snapshot.cells?.delivery == CellDelivery.DEVICE_CACHE) assertNull(snapshot.cells.subscriptionContext)
        assertEquals(snapshot.selectedId, snapshot.signal?.subscriptionContext)
        click("Request cell update")
        compose.waitUntil(3000) { vm.state.value.request?.status == CellRequestStatus.LOCAL_COOLDOWN }
        Log.i("AtlasCellValidation", "rate_guard: immediate explicit refresh rejected locally; modem rate-limit exhaustion not forced")
        click("Stop cellular collection")
        compose.waitForIdle()
        assertFalse(vm.state.value.collecting)
        val stopped = vm.state.value.eventCount
        SystemClock.sleep(1500)
        assertEquals(stopped, vm.state.value.eventCount)
        Log.i("AtlasCellValidation", "manual_stop: report count unchanged over 1500ms")

        click("Start cellular collection")
        compose.waitUntil(3000) { vm.state.value.collecting || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        val previewNavigation = InstrumentationRegistry.getArguments().getString("atlas.previewNavigation") == "true"
        if (previewNavigation) compose.onNodeWithTag("nav-history").performClick() else click("Back to dashboard")
        compose.waitForIdle()
        assertFalse(vm.state.value.collecting)
        val navigated = vm.state.value.eventCount
        SystemClock.sleep(1500)
        assertEquals(navigated, vm.state.value.eventCount)
        Log.i("AtlasCellValidation", "navigation_stop: report count unchanged over 1500ms")

        if (previewNavigation) compose.onNodeWithTag("nav-dashboard").performClick()

        click("Open Cellular")
        click("Start cellular collection")
        compose.waitUntil(3000) { vm.state.value.collecting || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(vm.state.value.collecting)
        val backgrounded = vm.state.value.eventCount
        SystemClock.sleep(1500)
        assertEquals(backgrounded, vm.state.value.eventCount)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertFalse("Resume must remain idle", vm.state.value.collecting)
        Log.i("AtlasCellValidation", "background_stop: report count unchanged over 1500ms; resume stayed idle")
        if (InstrumentationRegistry.getArguments().getString("atlas.storageHardware") == "true") verifyStorage()
    }
    private fun verifyStorage() {
        val vm = ViewModelProvider(compose.activity)[TelemetryViewModel::class.java]
        compose.waitUntil(5000) { vm.state.value.loaded || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        click("Back to dashboard"); click("History")
        compose.waitUntil(5000) { !vm.state.value.busy }
        click("Save session measurements")
        compose.waitUntil(10000) { !vm.state.value.busy && vm.state.value.message?.startsWith("Save finished") == true }
        assertNull(vm.state.value.error)
        val count = vm.state.value.count
        assertTrue("Real cellular reports were not persisted", count > 0)
        click("Save session measurements")
        compose.waitUntil(10000) { !vm.state.value.busy && vm.state.value.message?.startsWith("Save finished") == true }
        assertNull(vm.state.value.error)
        assertEquals("Repeated saves must not duplicate observations", count, vm.state.value.count)
        val repository = TelemetryRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val saved = runBlocking { repository.dao.exportRows() }
        assertTrue(saved.any { it.radioType == "CELLULAR" && it.value != null && it.measurementType.startsWith("NR") })
        assertTrue(saved.all { it.value != null || it.collectionStatus == "UNAVAILABLE" })
        Log.i("AtlasStorageValidation", "real_cellular_save: stored_records=${saved.size}, unavailable=${saved.count { it.value == null }}, repeated_save_added=0; no identifiers logged")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        val recreated = ViewModelProvider(compose.activity)[TelemetryViewModel::class.java]
        assertEquals(count, recreated.state.value.count)
        assertFalse(ViewModelProvider(compose.activity)[CellularViewModel::class.java].state.value.collecting)
        Log.i("AtlasStorageValidation", "activity_recreation: stored count preserved; collection stayed idle")
    }
    private fun click(text: String) {
        if (text == "History") { compose.onNodeWithTag("nav-history").performClick(); return }
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performClick()
    }
    private fun verifyCellularDefault(stage: String) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val wifi = app.getSystemService(WifiManager::class.java)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        compose.waitUntil(15_000) {
            val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            !wifi.isWifiEnabled && caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        assertFalse("Wi-Fi must already be disabled; test never changes settings", wifi.isWifiEnabled)
        assertTrue("Cellular default transport not reported", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
        assertFalse("Default network still reports Wi-Fi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
        Log.i("AtlasCellValidation", "data_transport[$stage]: wifi_enabled=${wifi.isWifiEnabled}, default_cellular=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}, default_wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}, vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}, internet_capability=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}, android_validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}; platform routing/validation only, no Atlas traffic probe")
    }
}
