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
import com.example.atlas.model.PermissionState
import com.example.atlas.model.RadioType
import com.example.atlas.permissions.RadioPermissionManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dashboardShowsAllRadiosAndHistoryIsHonest() {
        listOf("Bluetooth", "NFC", "Wi-Fi", "Cellular").forEach {
            compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Open $it"))
            compose.onNodeWithText("Open $it").assertIsDisplayed().performClick()
            compose.onNodeWithText("Hardware: Available").assertIsDisplayed()
            compose.onNodeWithText("Back to dashboard").performScrollTo().performClick()
        }
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("History"))
        compose.onNodeWithText("History").assertIsDisplayed().performClick()
        compose.onNodeWithText("No measurements collected").assertIsDisplayed()
    }

    @Test fun bluetoothPermissionIsRequestedByActionAndDenialIsSafe() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = RadioPermissionManager(app)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Preserve an existing user grant. On a clean install test denial through the OS dialog.
        if (permissions.state(RadioType.BLUETOOTH) == PermissionState.GRANTED) return
        assertFalse(device.hasObject(By.res("com.android.permissioncontroller", "permission_allow_button")))
        compose.onNodeWithText("Open Bluetooth").performScrollTo().performClick()
        compose.onNodeWithText("Check Bluetooth state").performScrollTo().performClick()
        val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_deny_button")), 5000)
        assertNotNull("Expected user-triggered Nearby devices dialog", deny)
        deny.click()
        compose.waitForIdle()
        compose.onNodeWithText("State: Permission required").performScrollTo().assertIsDisplayed()
        assertEquals(PermissionState.REQUIRED, permissions.state(RadioType.BLUETOOTH))
    }

    @Test fun wifiPermissionDenialLeavesCollectionDisabled() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        if (RadioPermissionManager(app).missingForWifiSurvey().isEmpty()) return
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Open Wi-Fi"))
        compose.onNodeWithText("Open Wi-Fi").performClick()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Start Wi-Fi survey"))
        compose.onNodeWithText("Start Wi-Fi survey").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Review Wi-Fi permissions"))
        compose.onNodeWithText("Review Wi-Fi permissions").performClick()
        val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_deny_button")), 5000)
        assertNotNull("Expected user-triggered location dialog", deny)
        deny.click()
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Start Wi-Fi survey"))
        compose.onNodeWithText("Start Wi-Fi survey").assertIsNotEnabled()
        assertTrue(RadioPermissionManager(app).missingForWifiSurvey().isNotEmpty())
    }
}
