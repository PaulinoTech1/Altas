package com.example.atlas

import android.content.pm.PackageManager
import android.nfc.NfcManager
import android.nfc.NfcAdapter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.atlas.acquisition.nfc.NfcViewModel
import android.util.Log
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** No tag or APDU is needed: verifies explicit foreground reader lifecycle only. */
@RunWith(AndroidJUnit4::class)
class NfcPhysicalInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun readerStartsAndStopsWhenLeavingNfc() {
        assumeTrue("Opt in to physical NFC reader test", InstrumentationRegistry.getArguments().getString("atlas.nfcHardware") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(app.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC))
        assumeTrue(app.getSystemService(NfcManager::class.java)?.defaultAdapter?.isEnabled == true)
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Open NFC"))
        compose.onNodeWithText("Open NFC").performClick()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Start NFC reader"))
        compose.onNodeWithText("Start NFC reader").performClick()
        compose.waitForIdle()
        val vm = ViewModelProvider(compose.activity)[NfcViewModel::class.java]
        assertTrue(vm.state.value.reading)
        compose.onNodeWithText("Stop NFC reader").assertIsDisplayed()
        compose.onNodeWithTag("nav-history").performClick()
        compose.waitForIdle()
        assertFalse(vm.state.value.reading)
    }

    @Test fun authorizedPhysicalTagIsDetectedWithoutCommandsOrPersistence() {
        assumeTrue("Opt in to physical tag presentation", InstrumentationRegistry.getArguments().getString("atlas.nfcTag") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(app.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC))
        assumeTrue(app.getSystemService(NfcManager::class.java)?.defaultAdapter?.isEnabled == true)
        val vm = ViewModelProvider(compose.activity)[NfcViewModel::class.java]
        assertTrue(vm.state.value.tags.isEmpty())
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Open NFC"))
        compose.onNodeWithText("Open NFC").performClick()
        compose.onNodeWithTag("atlas-content").performScrollToNode(hasText("Start NFC reader"))
        compose.onNodeWithText("Start NFC reader").performClick()
        compose.waitForIdle()
        assertTrue("Reader did not start: ${vm.state.value.status}, ${vm.state.value.error}", vm.state.value.reading)
        try { compose.waitUntil(timeoutMillis = 55_000) { vm.state.value.tags.isNotEmpty() || vm.state.value.error != null } }
        catch (_: ComposeTimeoutException) { fail("No tag callback within 55 seconds; reader status=${vm.state.value.status}, error=${vm.state.value.error}") }
        val report = vm.state.value.tags.firstOrNull()
        assertTrue("No tag delivered; reader status=${vm.state.value.status}, error=${vm.state.value.error}", report != null)
        Log.i("AtlasNfcValidation", "tag_detected=true technologies=${report!!.technologies.joinToString(",")}, ndef_supported=${report.ndefType != null}, ndef_records=${report.ndefRecords.size}, ndef_status=${report.ndefStatus}, iso_dep=${report.isoDep}; no identifier or payload logged")
        assertTrue(vm.state.value.exchanges.isEmpty())
        compose.onNodeWithTag("nav-history").performClick()
        compose.waitForIdle()
        assertFalse(vm.state.value.reading)
    }

    @Test fun platformReaderDetectsNfcAWithoutNdefOrAtlasState() {
        assumeTrue("Opt in to direct Android NFC reader test", InstrumentationRegistry.getArguments().getString("atlas.nfcPlatform") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = app.getSystemService(NfcManager::class.java)?.defaultAdapter
        assumeTrue(adapter?.isEnabled == true)
        val seen = AtomicReference<List<String>?>(null)
        val callback = NfcAdapter.ReaderCallback { tag -> seen.set(tag.techList.map { it.substringAfterLast('.') }) }
        compose.runOnUiThread {
            adapter!!.enableReaderMode(compose.activity, callback,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        }
        try {
            try { compose.waitUntil(timeoutMillis = 55_000) { seen.get() != null } }
            catch (_: ComposeTimeoutException) { fail("Android NfcAdapter delivered no NFC-A tag within 55 seconds") }
            Log.i("AtlasNfcValidation", "platform_tag_detected=true technologies=${seen.get().orEmpty().joinToString(",")}; no identifier, NDEF, APDU or payload read")
        } finally {
            compose.runOnUiThread { adapter!!.disableReaderMode(compose.activity) }
        }
    }
}
