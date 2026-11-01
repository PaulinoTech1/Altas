package com.example.atlas

import com.example.atlas.acquisition.wifi.*
import com.example.atlas.permissions.RadioPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun row(source: Long? = 1_000_000, delivery: WifiDelivery = WifiDelivery.CACHE_READ, bssid: String? = "00:11:22:33:44:55") = WifiObservation(
        "event-$source", "test", bssid, -60, 2412, 1, "20 MHz", null, null, "[WPA2-PSK-CCMP][ESS]",
        1_700_000_000_000, 2_000_000, source, 500_000, delivery
    )

    @Test fun channelMappingHandlesBandsAndSpecialCases() {
        mapOf(2412 to 1, 2437 to 6, 2472 to 13, 2484 to 14, 5180 to 36, 5500 to 100,
            5885 to 177, 5935 to 2, 5955 to 1, 5975 to 5, 7115 to 233, 58320 to 1, 69120 to 6).forEach { (freq, expected) ->
            assertEquals("$freq MHz", expected, WifiValues.channel(freq))
        }
    }
    @Test fun invalidAndOffGridFrequenciesAreUnavailable() {
        listOf(0, -1, 2400, 2413, 2480, 2483, 4900, 5181, 5900, 5940, 5956, 7120, 58321, 70200, Int.MAX_VALUE).forEach {
            assertNull("$it MHz", WifiValues.channel(it))
        }
    }
    @Test fun redactedIdentifiersAreNeverReportedAsActualAddresses() {
        listOf(null, "", "02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "invalid").forEach { assertNull(WifiValues.bssid(it)) }
        assertEquals("aa:bb:cc:dd:ee:ff", WifiValues.bssid("AA:BB:CC:DD:EE:FF"))
        assertNull(WifiValues.ssid("<unknown ssid>"))
        assertNull(WifiValues.ssid(""))
        assertEquals("test", WifiValues.ssid("te\u202Est\n"))
        assertNull(WifiValues.rssi(-127))
        assertNull(WifiValues.positive(0))
        assertEquals(-60, WifiValues.rssi(-60))
    }
    @Test fun widthCodesAreExplicitIncludingUnsupportedValues() {
        assertEquals("80+80 MHz", WifiValues.width(4))
        assertEquals("320 MHz", WifiValues.width(5))
        assertTrue(WifiValues.width(99).contains("unsupported"))
    }
    @Test fun freshnessDistinguishesCacheRecentStaleUnknownAndFutureSources() {
        assertEquals(WifiFreshness.CACHED, row().freshness(2_000_000))
        assertEquals(WifiFreshness.RECENT, row(delivery = WifiDelivery.UPDATED_BROADCAST).freshness(2_000_000))
        assertEquals(WifiFreshness.STALE, row(delivery = WifiDelivery.UPDATED_BROADCAST).freshness(32_000_001))
        assertEquals(WifiFreshness.UNKNOWN, row(source = null).freshness(2_000_000))
        assertEquals(WifiFreshness.UNKNOWN, row(source = 3_000_000).freshness(2_000_000))
        assertEquals(WifiFreshness.CACHED, row(source = 100_000, delivery = WifiDelivery.UPDATED_BROADCAST).freshness(2_000_000))
    }
    @Test fun rateGuardUsesRollingBudgetAndThirtySecondSpacing() {
        val guard = WifiScanRateGuard()
        assertEquals(0L, guard.reserve(0))
        assertEquals(29_999L, guard.reserve(1))
        assertEquals(0L, guard.reserve(30_000))
        assertEquals(0L, guard.reserve(60_000))
        assertEquals(0L, guard.reserve(90_000))
        assertEquals(20_000L, guard.reserve(100_000))
        assertEquals(0L, guard.reserve(120_000))
    }
    @Test fun historyDeduplicatesCacheAndRejectsUnknownIdentityOrTime() {
        val history = WifiHistoryStore(2, 2)
        history.add(listOf(row(), row(), row(bssid = null), row(source = null)))
        assertEquals(1, history.observations().size)
        history.add(listOf(row(source = 900_000)))
        assertEquals(1, history.observations().size)
        history.add(listOf(row(source = 1_100_000), row(source = 1_200_000)))
        assertEquals(2, history.observations().size)
        assertEquals(1_200_000L, history.observations().first().sourceElapsedMicros)
        assertEquals(3L, history.sampleCount)
        history.clear()
        assertTrue(history.observations().isEmpty())
    }
    @Test fun surveyPermissionsDoNotRequestNearbyDevicesOrBackgroundAccess() {
        assertEquals(setOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"),
            RadioPermissionManager.wifiSurveyPermissions.toSet())
    }
    @Test fun indexEvictionDoesNotTurnOldCacheIntoNewHistory() {
        val history = WifiHistoryStore(4, 1)
        history.add(listOf(row(source = 1_000_000, bssid = "A"), row(source = 1_100_000, bssid = "B")))
        history.add(listOf(row(source = 1_000_000, bssid = "A"), row(source = 900_000, bssid = "A")))
        assertEquals(2, history.observations().size)
        assertEquals(2L, history.sampleCount)
    }
    @Test fun surveyTimeoutClosesCollectorWithoutPollingOrAutomaticRestart() = runTest(dispatcher) {
        var closed = false
        var starts = 0
        val vm = WifiViewModel(object : WifiSurveyRepository {
            override fun survey() = flow<WifiEvent> {
                starts++
                try { emit(WifiEvent.Results(listOf(row()), WifiDelivery.CACHE_READ, 1)); awaitCancellation() }
                finally { closed = true }
            }
            override suspend fun requestScan() = error("No implicit ViewModel polling allowed")
        })
        assertFalse(vm.state.value.collecting)
        vm.start(); runCurrent()
        assertTrue(vm.state.value.collecting)
        advanceTimeBy(60_001); runCurrent()
        assertTrue(closed)
        assertEquals(1, starts)
        assertFalse(vm.state.value.collecting)
        assertEquals("Survey time limit reached", vm.state.value.status)
        assertEquals(1, vm.state.value.history.size)
    }
    @Test fun stopCancelsAndResumeRequiresAnotherUserAction() = runTest(dispatcher) {
        var closed = false
        val vm = WifiViewModel(object : WifiSurveyRepository {
            override fun survey() = flow<WifiEvent> { try { awaitCancellation() } finally { closed = true } }
            override suspend fun requestScan() = error("Unexpected request")
        })
        vm.start(); runCurrent(); vm.stop(); advanceUntilIdle()
        assertTrue(closed)
        assertFalse(vm.state.value.collecting)
        assertNull(vm.state.value.error)
    }
    @Test fun permissionFailureAndRequestRejectionAreNotSuccessShaped() = runTest(dispatcher) {
        val vm = WifiViewModel(object : WifiSurveyRepository {
            override fun survey() = flow<WifiEvent> { throw SecurityException() }
            override suspend fun requestScan() = error("Unexpected request")
        })
        vm.start(); advanceUntilIdle()
        assertEquals("Survey failed", vm.state.value.status)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.rows.isEmpty())
        val rejected = WifiScanRequest(WifiRequestStatus.OS_REJECTED, "OS request rejected; cause unknown")
        val other = WifiViewModel(object : WifiSurveyRepository {
            override fun survey() = flow<WifiEvent> { emit(WifiEvent.Request(rejected)); awaitCancellation() }
            override suspend fun requestScan() = rejected
        })
        other.start(); runCurrent()
        assertEquals(WifiRequestStatus.OS_REJECTED, other.state.value.request?.status)
        other.stop(); advanceUntilIdle()
    }
}
