package com.example.atlas

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.StaleObjectException
import com.example.atlas.model.PermissionState
import com.example.atlas.model.RadioType
import com.example.atlas.permissions.RadioPermissionManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import android.util.Log
import com.example.atlas.telemetry.TelemetryRepository
import androidx.lifecycle.ViewModelProvider
import com.example.atlas.acquisition.bluetooth.BluetoothViewModel
import com.example.atlas.acquisition.wifi.WifiViewModel
import com.example.atlas.acquisition.cellular.CellularViewModel
import com.example.atlas.acquisition.nfc.NfcViewModel
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class DashboardInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private companion object {
        /**
         * Only the plain "Deny" button. Anchored at both ends so it can never match
         * permission_deny_and_dont_ask_again_button, which permanently denies the
         * permission on the device (audit finding A1-003).
         */
        val PLAIN_DENY: Pattern = Pattern.compile("^[\\w.]+:id/permission_deny_button$")
        val DONT_ASK_AGAIN: Pattern = Pattern.compile("^[\\w.]+:id/permission_deny_and_dont_ask_again_button$")
        /** Detection only (never clicked): any allow or deny control of an open OS permission dialog. */
        val ANY_PERMISSION_CONTROL: Pattern = Pattern.compile(".*:id/permission_(allow|deny).*")

        /**
         * Tests that open a real OS permission dialog are opt-in, so a run on a personal
         * phone never changes its permission state by default. Enable with
         * `am instrument -e atlasAllowPermissionDialogs true ...` on an emulator or test device.
         */
        fun permissionDialogsAllowed(): Boolean =
            InstrumentationRegistry.getArguments().getString("atlasAllowPermissionDialogs") == "true"
    }

    /**
     * Presses the plain "Deny" button of an open permission dialog, at most once.
     * Returns true if it denied. If Android only offers "Deny & don't ask again", it
     * dismisses the dialog with Back (which does not set the fixed flag) and skips the test.
     */
    private fun denyOnceSafely(device: UiDevice, timeoutMs: Long): Boolean {
        val deny = device.wait(Until.findObject(By.res(PLAIN_DENY)), timeoutMs)
        if (deny != null) {
            Log.i("AtlasPermissionValidation", "Denial control: ${deny.resourceName}")
            deny.click()
            return true
        }
        if (device.hasObject(By.res(DONT_ASK_AGAIN))) {
            Log.w("AtlasPermissionValidation", "Only 'don't ask again' offered; not pressed, dismissing with Back")
            device.pressBack()
            assumeTrue("Android only offered 'Deny & don't ask again'; skipped to protect device state", false)
        }
        return false
    }

    @Test fun persistentNavigationPreservesPageAndLeavesCollectorsIdle() {
        listOf("nav-dashboard", "nav-history", "nav-settings", "nav-project").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("nav-project").performClick()
        compose.onNodeWithText("NFC reader · Tag discovery verified").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("NFC reader · Tag discovery verified").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithText("Private telemetry storage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav-history").performClick()
        compose.onNodeWithText("Save session measurements").performScrollTo().assertIsDisplayed()
        assertFalse(ViewModelProvider(compose.activity)[BluetoothViewModel::class.java].state.value.scanning)
        assertFalse(ViewModelProvider(compose.activity)[WifiViewModel::class.java].state.value.collecting)
        assertFalse(ViewModelProvider(compose.activity)[CellularViewModel::class.java].state.value.collecting)
        assertFalse(ViewModelProvider(compose.activity)[NfcViewModel::class.java].state.value.reading)
    }

    @Test fun dashboardShowsAllRadiosAndHistoryIsHonest() {
        listOf("Bluetooth", "NFC", "Wi-Fi", "Cellular").forEach {
            compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Open $it"))
            compose.onNodeWithText("Open $it").assertIsDisplayed().performClick()
            compose.onNodeWithText("Hardware: Available", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Back to dashboard").performScrollTo().performClick()
        }
        compose.onNodeWithTag("nav-history").assertIsDisplayed().performClick()
        val count = runBlocking { TelemetryRepository.get(compose.activity).dao.totalCount() }
        compose.onNodeWithText("Saved records: $count").assertIsDisplayed()
        if (count == 0) compose.onNodeWithText("No measurements collected").assertIsDisplayed()
    }

    @Test fun bluetoothPermissionIsRequestedByActionAndDenialIsSafe() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = RadioPermissionManager(app)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Preserve an existing user grant. On a clean install test denial through the OS dialog.
        if (permissions.state(RadioType.BLUETOOTH) == PermissionState.GRANTED) return
        assumeTrue("Permission-dialog tests are opt-in", permissionDialogsAllowed())
        assertFalse(device.hasObject(By.res("com.android.permissioncontroller", "permission_allow_button")))
        compose.onNodeWithText("Open Bluetooth").performScrollTo().performClick()
        compose.onNodeWithText("Check Bluetooth state").performScrollTo().performClick()
        if (denyOnceSafely(device, 5000)) {
            Log.i("AtlasPermissionValidation", "Nearby devices: ordinary denial dialog tested")
        } else {
            assertFalse("Unexpected unresolved permission dialog", device.hasObject(By.res(ANY_PERMISSION_CONTROL)))
            assertFalse("Android should not offer rationale for the blocked permission", compose.activity.shouldShowRequestPermissionRationale(android.Manifest.permission.BLUETOOTH_CONNECT))
            compose.onNodeWithText(compose.activity.getString(R.string.permission_settings_help)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("App permission settings").performScrollTo().assertIsDisplayed()
            Log.i("AtlasPermissionValidation", "Nearby devices: no OS prompt; denied state and settings recovery guidance tested; ordinary dialog branch NOT RUN")
        }
        compose.waitForIdle()
        compose.onNodeWithText("State: Permission required").performScrollTo().assertIsDisplayed()
        assertEquals(PermissionState.REQUIRED, permissions.state(RadioType.BLUETOOTH))
    }

    @Test fun wifiPermissionDenialLeavesCollectionDisabled() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        if (RadioPermissionManager(app).missingForWifiSurvey().isEmpty()) return
        assumeTrue("Permission-dialog tests are opt-in", permissionDialogsAllowed())
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Open Wi-Fi"))
        compose.onNodeWithText("Open Wi-Fi").performClick()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Start Wi-Fi survey"))
        compose.onNodeWithText("Start Wi-Fi survey").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Review Wi-Fi permissions"))
        compose.onNodeWithText("Review Wi-Fi permissions").performClick()
        assertTrue("Expected user-triggered location dialog", denyOnceSafely(device, 5000))
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Start Wi-Fi survey"))
        compose.onNodeWithText("Start Wi-Fi survey").assertIsNotEnabled()
        assertTrue(RadioPermissionManager(app).missingForWifiSurvey().isNotEmpty())
    }
    @Test fun cellularPermissionDenialLeavesCollectionDisabled() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        if (RadioPermissionManager(app).missingForCellular().isEmpty()) return
        assumeTrue("Permission-dialog tests are opt-in", permissionDialogsAllowed())
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Open Cellular"))
        compose.onNodeWithText("Open Cellular").performClick()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Review cellular permissions"))
        compose.onNodeWithText("Review cellular permissions").performClick()
        var denied = 0
        repeat(4) {
            try {
                if (denyOnceSafely(device, 1500)) denied++
            } catch (_: StaleObjectException) {
                Log.i("AtlasPermissionValidation", "OS permission dialog transitioned; reacquiring within bounded attempts")
            }
        }
        assertTrue("Expected contextual permission dialog", denied > 0)
        assertFalse("An unresolved permission dialog remains", device.hasObject(By.res(ANY_PERMISSION_CONTROL)))
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Start cellular collection"))
        compose.onNodeWithText("Start cellular collection").assertIsNotEnabled()
        assertTrue(RadioPermissionManager(app).missingForCellular().isNotEmpty())
    }
}
