package com.example.atlas

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.atlas.acquisition.wifi.AndroidLanDiscovery
import com.example.atlas.acquisition.wifi.LanConnection
import com.example.atlas.acquisition.wifi.LanDiscoveryViewModel
import com.example.atlas.acquisition.wifi.LanProbe
import com.example.atlas.acquisition.wifi.LanProbeResult
import com.example.atlas.acquisition.wifi.LanServiceBrowse
import com.example.atlas.acquisition.wifi.LanTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanDiscoveryTimeoutInstrumentedTest {
    @Test fun slowProbeEndsWithVisibleTimeLimitStatus() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wifi = AndroidLanDiscovery(context).connection()
        val slow = LanProbe { _, _, _ ->
            Thread.sleep(300)
            LanProbeResult.TIMEOUT
        }
        val target = LanTarget(wifi.target.localAddress, wifi.target.prefixLength, listOf("10.0.0.2"))
        val viewModel = LanDiscoveryViewModel(
            connection = { LanConnection(target, slow, wifi.network) },
            serviceBrowse = LanServiceBrowse { _, _, _ -> 0 },
            scanLimitMillis = 50
        )
        viewModel.start()
        val result = withTimeout(5_000) { viewModel.state.first { !it.running && it.status.startsWith("Time limit reached") } }
        assertEquals("Time limit reached; partial results", result.status)
        assertFalse(result.running)
        assertTrue((result.progress?.checked ?: 0) < (result.progress?.total ?: 0))
    }
}
