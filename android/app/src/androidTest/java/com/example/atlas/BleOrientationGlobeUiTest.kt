package com.example.atlas

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import com.example.atlas.acquisition.bluetooth.Advertisement
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.acquisition.bluetooth.PhoneOrientation
import com.example.atlas.ui.viz.BleOrientationGlobe
import com.example.atlas.ui.viz.OrientationGlobeMath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BleOrientationGlobeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingPointIsolatesAddressAndShowAllRestoresPlot() {
        var chosenAddress by mutableStateOf<String?>(null)
        val samples = listOf(
            BleObservation("a", "AA", 2L, 2L, null, -50, true, Advertisement(name = "First"),
                phoneOrientation = PhoneOrientation(1f, 0f, 0f, 2L), orientationSessionId = "scan"),
            BleObservation("b", "BB", 1L, 1L, null, -80, true, Advertisement(name = "Second"),
                phoneOrientation = PhoneOrientation(-1f, 0f, 0f, 1L), orientationSessionId = "scan")
        )
        compose.setContent {
            MaterialTheme { BleOrientationGlobe(samples, false, chosenAddress, null) { chosenAddress = it } }
        }
        val plotted = OrientationGlobeMath.project(samples.first(), 0.55f, -0.35f, 1f)!!
        compose.onNodeWithTag("ble-orientation-globe").performTouchInput {
            val radius = minOf(width, height) * 0.43f
            click(Offset(center.x + plotted.x * radius, center.y + plotted.y * radius))
        }
        compose.onNodeWithText("Isolated address: AA (1 paired samples)").assertExists()
        val isolated = compose.onNodeWithTag("ble-orientation-globe")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        org.junit.Assert.assertTrue(isolated.contains("1 visible of 2"))
        compose.onNodeWithText("Next address").performClick()
        compose.onNodeWithText("Isolated address: BB (1 paired samples)").assertExists()
        compose.onNodeWithText("Previous address").performClick()
        compose.onNodeWithText("Isolated address: AA (1 paired samples)").assertExists()
        compose.onNodeWithText("Show all addresses").performClick()
        compose.onNodeWithText("All observed addresses (2 paired samples)").assertExists()
        val restored = compose.onNodeWithTag("ble-orientation-globe")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        org.junit.Assert.assertTrue(restored.contains("2 visible of 2"))
    }

    @Test fun tapAndTraversalExposeExactDbm() {
        var chosenAddress: String? = null
        val samples = listOf(
            BleObservation("new", "AA", 2L, 2L, null, -50, true, Advertisement(name = "Test peripheral"),
                phoneOrientation = PhoneOrientation(0f, 0f, 1f, 2L), orientationSessionId = "scan"),
            BleObservation("old", "AA", 1L, 1L, null, -70, true, Advertisement(name = "Test peripheral"),
                phoneOrientation = PhoneOrientation(0f, 0f, 1f, 1L), orientationSessionId = "scan")
        )
        compose.setContent {
            MaterialTheme { BleOrientationGlobe(samples, false, chosenAddress, null) { chosenAddress = it } }
        }
        compose.onNodeWithText("Selected point 1/2: -50 dBm", substring = true).assertExists()
        val plotted = OrientationGlobeMath.project(samples.first(), 0.55f, -0.35f, 1f)!!
        compose.onNodeWithTag("ble-orientation-globe").performTouchInput {
            val radius = minOf(width, height) * 0.43f
            click(Offset(center.x + plotted.x * radius, center.y + plotted.y * radius))
        }
        compose.runOnIdle { assertEquals("AA", chosenAddress) }
        compose.onNodeWithText("Older point").performClick()
        compose.onNodeWithText("Selected point 2/2: -70 dBm", substring = true).assertExists()
        val globe = compose.onNodeWithTag("ble-orientation-globe")
        val description = globe.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        org.junit.Assert.assertTrue(description.contains("X, Y, Z axes") && description.contains("-100 dBm"))
        val before = globe.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        globe.performTouchInput { swipe(center, Offset(center.x + width * 0.2f, center.y), 250) }
        val after = globe.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        org.junit.Assert.assertNotEquals("Drag did not orbit the view", before, after)
    }
}
