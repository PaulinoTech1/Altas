package com.example.atlas

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.atlas.ui.ClearHistoryControl
import com.example.atlas.ui.UnsavedCue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClearHistoryControlUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyHistoryCannotBeCleared() {
        compose.setContent { ClearHistoryControl("Clear BLE history", false, false, UnsavedCue(0, false, true)) {} }
        compose.onNodeWithText("Clear BLE history").assertIsNotEnabled()
    }

    @Test fun unsavedHistoryRequiresExplicitConfirmation() {
        var clears = 0
        compose.setContent { ClearHistoryControl("Clear BLE history", true, false, UnsavedCue(3, false, true)) { clears++ } }
        compose.onNodeWithText("Clear BLE history").performClick()
        compose.onNodeWithText("3 unsaved measurements may be lost.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText("Clear BLE history").performClick()
        compose.onNodeWithText("Clear temporary history").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
    }
}
