package com.example.atlas

import com.example.atlas.data.CapabilityRepository
import com.example.atlas.model.Availability
import com.example.atlas.model.CollectionState
import com.example.atlas.model.PermissionState
import com.example.atlas.model.RadioCapability
import com.example.atlas.model.RadioType
import com.example.atlas.permissions.RadioPermissionManager
import com.example.atlas.ui.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FoundationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun unknownIsNotAZeroMeasurement() {
        val value = RadioCapability(RadioType.CELLULAR)
        assertEquals(Availability.UNKNOWN, value.availability)
        assertNull(value.lastMeasurementEpochMillis)
        assertNull(value.checkedAtEpochMillis)
        assertEquals(CollectionState.NOT_IMPLEMENTED, value.collection)
    }

    @Test fun permissionPolicyOnlyRequestsBluetoothStateAccess() {
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"),
            RadioPermissionManager.statePermissions(RadioType.BLUETOOTH))
        RadioType.entries.filter { it != RadioType.BLUETOOTH }.forEach {
            assertTrue(RadioPermissionManager.statePermissions(it).isEmpty())
        }
    }

    @Test fun deniedAndGrantedAreDistinct() {
        assertEquals(PermissionState.NOT_REQUIRED, RadioPermissionManager.permissionState(emptyList(), emptyList()))
        assertEquals(PermissionState.REQUIRED, RadioPermissionManager.permissionState(listOf("p"), listOf("p")))
        assertEquals(PermissionState.GRANTED, RadioPermissionManager.permissionState(listOf("p"), emptyList()))
    }

    @Test fun refreshPublishesRepositorySnapshot() = runTest(dispatcher) {
        val expected = RadioType.entries.map { RadioCapability(it, availability = Availability.AVAILABLE) }
        val vm = DashboardViewModel(object : CapabilityRepository {
            override suspend fun snapshot() = expected
        })
        vm.refresh()
        advanceUntilIdle()
        assertEquals(expected, vm.state.value.radios)
        assertFalse(vm.state.value.refreshing)
        assertNull(vm.state.value.error)
    }

    @Test fun repositoryFailureRemainsVisible() = runTest(dispatcher) {
        val vm = DashboardViewModel(object : CapabilityRepository {
            override suspend fun snapshot(): List<RadioCapability> = error("service failed")
        })
        vm.refresh()
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.refreshing)
        assertTrue(vm.state.value.radios.all { it.availability == Availability.UNKNOWN })
    }

    @Test fun pauseCancelsInFlightRefreshWithoutReportingFailure() = runTest(dispatcher) {
        var cancelled = false
        val vm = DashboardViewModel(object : CapabilityRepository {
            override suspend fun snapshot(): List<RadioCapability> {
                try { awaitCancellation() } finally { cancelled = true }
            }
        })
        vm.refresh()
        runCurrent()
        assertTrue(vm.state.value.refreshing)
        vm.pause()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.refreshing)
    }
}
