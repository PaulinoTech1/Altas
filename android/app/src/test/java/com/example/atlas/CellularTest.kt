package com.example.atlas

import com.example.atlas.acquisition.cellular.*
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
class CellularTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun row(source: Long = 1000) = CellRecord("LTE", true, 1, 1, 0, 1, 100, "001", "01", CellularValues.lte(-95, -12, 12), source)
    private fun batch(sequence: Long = 1, context: Int? = 1, delivery: CellDelivery = CellDelivery.CALLBACK) = CellBatch(
        "event-$sequence", listOf(row(sequence * 1000)), 1, context, delivery, sequence * 1000,
        sequence * 1000 + 100, 1, "synthetic-test"
    )

    @Test fun lteRssnrUsesPublicApiDbWithoutTenthsScaling() {
        val metrics = CellularValues.lte(-95, -12, 12)
        assertEquals(CellularMetric("LTE RSSNR", 12, "dB"), metrics.last())
        assertEquals(CellularMetric("LTE RSRP", -95, "dBm"), metrics.first())
    }
    @Test fun sentinelsAreUnavailableAndRealZeroSinrIsPreserved() {
        assertTrue(CellularValues.lte(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE).all { it.value == null })
        assertTrue(CellularValues.nr(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE).all { it.value == null })
        assertEquals(0, CellularValues.nr(-100, -12, 0).last().value)
        assertEquals(0, CellularValues.lte(-100, -12, 0).last().value)
    }
    @Test fun metricRangesRejectImpossibleValues() {
        assertNull(CellularValues.nr(-180, -12, 0).first().value)
        assertNull(CellularValues.nr(-100, -60, 0)[1].value)
        assertNull(CellularValues.lte(-90, -12, 300).last().value)
        assertEquals(-156, CellularValues.nr(-156, -43, -23).first().value)
    }
    @Test fun identifiersUseSeparateIntAndLongSentinelsAndAllowPciZero() {
        assertNull(CellularValues.identifier(Int.MAX_VALUE))
        assertNull(CellularValues.identifier(-1))
        assertNull(CellularValues.identifier(Long.MAX_VALUE, 0xfffffffffL))
        assertEquals(0, CellularValues.identifier(0, 503))
        assertEquals(0xfffffffffL, CellularValues.identifier(0xfffffffffL, 0xfffffffffL))
        assertEquals(Int.MAX_VALUE.toLong(), CellularValues.identifier(Int.MAX_VALUE.toLong(), 0xfffffffffL))
    }
    @Test fun requestResponseDoesNotMakeOldSourceTimestampFresh() {
        assertEquals("Cached — device-wide", batch(context = null, delivery = CellDelivery.DEVICE_CACHE).freshness(row(), 2000))
        assertEquals("Cached source timestamp", batch(delivery = CellDelivery.REQUEST_RESPONSE).copy(referenceElapsedMillis = 1500).freshness(row(), 2000))
        assertEquals("Recent platform report", batch().freshness(row(), 2000))
        assertEquals("Stale", batch().freshness(row(), 32_000))
        assertEquals("Source age unavailable", batch().freshness(row(3000), 2000))
        assertNull(CellularValues.age(null, 1000))
    }
    @Test fun carrierHintAndServingRoleRemainDistinctFromTechnology() {
        assertEquals("LTE", CellularValues.technology(13))
        assertEquals("NR", CellularValues.technology(20))
        assertTrue(CellularValues.carrierHint(3).contains("display"))
        assertTrue(CellularValues.connection(2, true).contains("Secondary"))
        assertTrue(CellularValues.connection(Int.MAX_VALUE, false).contains("unavailable"))
    }
    @Test fun rateGuardRetainsThirtySecondSpacing() {
        val guard = CellUpdateRateGuard()
        assertEquals(0L, guard.reserve(1000))
        assertEquals(29_999L, guard.reserve(1001))
        assertEquals(0L, guard.reserve(31_000))
    }
    @Test fun permissionsExcludeNumbersPrivilegedAndBackgroundAccess() {
        assertEquals(setOf("android.permission.READ_PHONE_STATE", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION"), RadioPermissionManager.cellularPermissions.toSet())
    }
    @Test fun sessionTimeoutClosesCollectorWithoutAutomaticRestart() = runTest(dispatcher) {
        var closed = false
        var starts = 0
        val vm = CellularViewModel(object : CellularRepository {
            override fun observe(subscriptionId: Int?) = flow<CellularEvent> {
                starts++
                try { emit(CellularEvent.Cells(batch())); awaitCancellation() } finally { closed = true }
            }
            override suspend fun requestUpdate(subscriptionId: Int) = error("No polling")
        })
        assertFalse(vm.state.value.collecting)
        vm.start(); runCurrent(); advanceTimeBy(60_001); runCurrent()
        assertTrue(closed)
        assertEquals(1, starts)
        assertFalse(vm.state.value.collecting)
        assertEquals("Cellular session time limit reached", vm.state.value.status)
    }
    @Test fun historiesAreBoundedAndIdenticalCellReportsAreDeduplicated() = runTest(dispatcher) {
        val vm = CellularViewModel(object : CellularRepository {
            override fun observe(subscriptionId: Int?) = flow {
                emit(CellularEvent.Cells(batch())); emit(CellularEvent.Cells(batch()))
                repeat(70) { emit(CellularEvent.Cells(batch(it + 2L))) }
                repeat(140) { emit(CellularEvent.Signal(SignalReport(emptyList(), it.toLong(), it.toLong(), null, 1))) }
            }
            override suspend fun requestUpdate(subscriptionId: Int) = error("No polling")
        })
        vm.start(); advanceUntilIdle()
        assertEquals(64, vm.state.value.history.size)
        assertEquals(128, vm.state.value.signalHistory.size)
        assertEquals("event-71", vm.state.value.history.first().eventId)
    }
    @Test fun changingSubscriptionStopsAndDoesNotRelabelOldSignalHistory() = runTest(dispatcher) {
        val starts = mutableListOf<Int?>()
        val vm = CellularViewModel(object : CellularRepository {
            override fun observe(subscriptionId: Int?) = flow<CellularEvent> {
                starts += subscriptionId
                emit(CellularEvent.Session(listOf(CellularSubscription(1, 0), CellularSubscription(2, 1)), subscriptionId ?: 1, "synthetic"))
                emit(CellularEvent.Signal(SignalReport(emptyList(), 1, 1, null, subscriptionId ?: 1)))
                awaitCancellation()
            }
            override suspend fun requestUpdate(subscriptionId: Int) = error("No polling")
        })
        vm.start(); runCurrent(); vm.select(2); runCurrent()
        assertFalse(vm.state.value.collecting)
        assertEquals(2, vm.state.value.selectedId)
        assertNull(vm.state.value.signal)
        assertEquals(1, vm.state.value.signalHistory.first().subscriptionContext)
        assertEquals(1, starts.size)
        vm.start(); runCurrent()
        assertEquals(listOf(null, 2), starts)
        assertEquals(2, vm.state.value.signal?.subscriptionContext)
        vm.stop(); advanceUntilIdle()
    }
    @Test fun deniedAndUnsupportedAccessAreVisibleFailures() = runTest(dispatcher) {
        listOf(SecurityException(), UnsupportedOperationException()).forEach { failure ->
            val vm = CellularViewModel(object : CellularRepository {
                override fun observe(subscriptionId: Int?) = flow<CellularEvent> { throw failure }
                override suspend fun requestUpdate(subscriptionId: Int) = error("No request")
            })
            vm.start(); advanceUntilIdle()
            assertEquals("Cellular collection failed", vm.state.value.status)
            assertNotNull(vm.state.value.error)
            assertNull(vm.state.value.cells)
        }
    }
    @Test fun stopCancelsPendingWaitWithoutPretendingTheModemTimedOut() = runTest(dispatcher) {
        val vm = CellularViewModel(object : CellularRepository {
            override fun observe(subscriptionId: Int?) = flow<CellularEvent> {
                emit(CellularEvent.Update(CellUpdateResult(CellRequestStatus.PENDING, "synthetic pending")))
                awaitCancellation()
            }
            override suspend fun requestUpdate(subscriptionId: Int) = error("No polling")
        })
        vm.start(); runCurrent(); vm.stop(); runCurrent()
        assertEquals(CellRequestStatus.CANCELLED, vm.state.value.request?.status)
        assertFalse(vm.state.value.collecting)
        assertNull(vm.state.value.error)
    }
}
