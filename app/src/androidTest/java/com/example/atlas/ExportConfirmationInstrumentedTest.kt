package com.example.atlas

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.atlas.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cancels before opening the document picker; leaves saved records and preferences alone. */
@RunWith(AndroidJUnit4::class)
class ExportConfirmationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fullExportRequiresExplicitSecondAction() {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithText("Private telemetry storage").performScrollTo().assertIsDisplayed()
        val redacted = runBlocking { TelemetryRepository.get(compose.activity).preferences.values.first().redactExport }
        if (redacted) compose.onNodeWithTag("export-redaction").performClick()
        compose.onNodeWithText("Export JSON including identifiers").performScrollTo().performClick()
        compose.onNodeWithText("Export identifiers?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Export identifiers?").assertDoesNotExist()
    }
}
