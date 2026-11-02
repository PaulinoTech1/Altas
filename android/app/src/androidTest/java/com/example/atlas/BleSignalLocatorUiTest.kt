package com.example.atlas

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.atlas.acquisition.bluetooth.Advertisement
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.ui.viz.BleSignalLocator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BleSignalLocatorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchNeedsTargetAndExplicitStartAndStop() {
        var target by mutableStateOf<String?>(null)
        var scanning by mutableStateOf(false)
        var starts = 0
        var stops = 0
        val now = SystemClock.elapsedRealtimeNanos()
        val sample = BleObservation("e1", "AA", System.currentTimeMillis(), now, now, -63, false,
            Advertisement(name = "Test device"), orientationSessionId = "scan")
        compose.setContent {
            MaterialTheme {
                BleSignalLocator(listOf(sample), target, scanning, null, true, null,
                    onStart = { starts++; scanning = true }, onStop = { stops++; scanning = false })
            }
        }
        compose.onNodeWithText("Start 20s focused search").assertIsNotEnabled()
        compose.runOnIdle { target = "AA" }
        compose.onNodeWithText("Scan ended", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText("Start 20s focused search").performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        compose.onNodeWithText("Stop BLE search").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
}
