package com.example.atlas

import com.example.atlas.acquisition.bluetooth.*
import com.example.atlas.permissions.RadioPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun event(address: String = "test", sequence: Long = 1) = BleObservation(
        "event-$sequence", address, sequence, sequence * 1_000_000_000, sequence * 1_000_000_000 - 2_000_000,
        -65, true, Advertisement()
    )

    @Test fun parserDecodesNamesUuidsManufacturerAndSignedPower() {
        val parsed = AdvertisementParser.parse(byteArrayOf(
            2, 1, 6, 4, 9, 65, 66, 67, 3, 3, 0x0F, 0x18,
            5, -1, 0x4C, 0, 1, 2, 2, 0x0A, -12
        ))
        assertEquals("ABC", parsed.name)
        assertEquals(listOf("0000180f-0000-1000-8000-00805f9b34fb"), parsed.serviceUuids)
        assertEquals(listOf(ManufacturerData(0x004c, "0102")), parsed.manufacturerData)
        assertEquals(-12, parsed.txPowerDbm)
        assertFalse(parsed.malformed)
    }

    @Test fun uuid128And32UseBluetoothLittleEndian() {
        val uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val big = java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        val parsed = AdvertisementParser.parse(byteArrayOf(17, 7) + big.reversedArray() + byteArrayOf(5, 5, 0x78, 0x56, 0x34, 0x12))
        assertEquals(listOf(uuid.toString(), "12345678-0000-1000-8000-00805f9b34fb"), parsed.serviceUuids)
    }

    @Test fun malformedLengthsDoNotReadPastPacket() {
        assertTrue(AdvertisementParser.parse(byteArrayOf(6, 9, 65)).malformed)
        assertTrue(AdvertisementParser.parse(byteArrayOf(2, 3, 1)).malformed)
        assertTrue(AdvertisementParser.parse(byteArrayOf(2, -1, 0)).malformed)
        assertFalse(AdvertisementParser.parse(byteArrayOf(0, 127, -1)).malformed)
        assertNull(AdvertisementParser.parse(null).name)
        assertTrue(AdvertisementParser.parse(ByteArray(1800)).truncated)
        assertEquals(AdvertisementParser.MAX_BYTES * 2, AdvertisementParser.parse(ByteArray(1800)).rawHex.length)
    }

    @Test fun parserIsBoundedForArbitraryUntrustedPackets() {
        val random = java.util.Random(17)
        repeat(500) {
            val input = ByteArray(random.nextInt(1800)).also(random::nextBytes)
            val output = AdvertisementParser.parse(input)
            assertTrue(output.rawHex.length <= 3300)
        }
    }

    @Test fun completeNameWinsAndControlsAreRemoved() {
        val output = AdvertisementParser.parse(byteArrayOf(3, 9, 65, 10, 2, 8, 66))
        assertEquals("A", output.name)
    }

    @Test fun historyHasExplicitBoundsAndTracksRepeatedAddress() {
        val history = BleHistoryStore(2, 3)
        history.add(event("A", 1)); history.add(event("A", 2))
        assertEquals(2L, history.devices().first().receivedCount)
        assertEquals(1L, history.devices().first().firstSeenMillis)
        history.add(event("B", 3)); history.add(event("C", 4))
        assertEquals(listOf("C", "B"), history.devices().map { it.latest.address })
        assertEquals(listOf("event-4", "event-3", "event-2"), history.observations().map { it.eventId })
        assertEquals(4L, history.receivedCount)
        history.clear()
        assertTrue(history.observations().isEmpty())
    }

    @Test fun freshnessUsesSourceMonotonicTime() {
        assertEquals(2L, event().ageMillis(event().receivedElapsedNanos))
        assertNull(event().copy(sourceElapsedNanos = null).ageMillis(1))
        assertNull(event().ageMillis(1))
    }

    @Test fun surveyPolicyIncludesLocationWithoutAdvertisingOrBackgroundAccess() {
        assertEquals(setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"),
            RadioPermissionManager.bleScanPermissions.toSet())
    }

    @Test fun timeoutClosesScanAndPreservesActualObservation() = runTest(dispatcher) {
        var closed = false
        val vm = BluetoothViewModel(object : BleScanner {
            override fun scan(config: BleScanConfig) = flow {
                try { emit(event()); awaitCancellation() } finally { closed = true }
            }
        }, FakeGatt())
        vm.start(); runCurrent()
        assertTrue(vm.state.value.scanning)
        // B2: the countdown fields are set for the running scan and cleared when it ends.
        assertNotNull(vm.state.value.sessionStartedAtMillis)
        assertEquals(10_000L, vm.state.value.sessionLimitMillis)
        advanceTimeBy(10_001); runCurrent()
        assertFalse(vm.state.value.scanning)
        assertNull(vm.state.value.sessionStartedAtMillis)
        assertTrue(closed)
        assertEquals("Scan time limit reached", vm.state.value.status)
        assertEquals(1, vm.state.value.devices.size)
    }

    @Test fun stopRejectsLateCallbacksAndRequiresExplicitRestart() = runTest(dispatcher) {
        var closed = false
        var starts = 0
        val gatt = FakeGatt()
        val vm = BluetoothViewModel(object : BleScanner {
            override fun scan(config: BleScanConfig) = flow<BleObservation> {
                starts++
                try { awaitCancellation() } finally { closed = true }
            }
        }, gatt)
        assertFalse(vm.state.value.scanning)
        vm.start(); runCurrent(); vm.stopAll(); advanceUntilIdle()
        assertTrue(closed)
        assertFalse(vm.state.value.scanning)
        assertEquals(1, starts)
        assertEquals(1, gatt.disconnects)
        assertNull(vm.state.value.error)
    }

    @Test fun securityFailureIsNotAnEmptySuccessfulScan() = runTest(dispatcher) {
        val vm = BluetoothViewModel(object : BleScanner {
            override fun scan(config: BleScanConfig) = flow<BleObservation> { throw SecurityException() }
        }, FakeGatt())
        vm.start(); advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertEquals("Scan failed", vm.state.value.status)
        assertTrue(vm.state.value.history.isEmpty())
    }

    @Test fun connectionMustBeExplicitAndReferenceObservedConnectableDevice() = runTest(dispatcher) {
        val gatt = FakeGatt()
        val vm = BluetoothViewModel(object : BleScanner {
            override fun scan(config: BleScanConfig) = flow { emit(event("A")) }
        }, gatt)
        vm.connect("unknown")
        assertNull(gatt.connected)
        vm.start(); advanceUntilIdle()
        assertNull(gatt.connected)
        vm.connect("A")
        assertEquals("A", gatt.connected)
    }

    private class FakeGatt : GattClient {
        override val state = MutableStateFlow(GattState())
        var disconnects = 0
        var connected: String? = null
        override fun connect(address: String) { connected = address }
        override fun read(key: String) {}
        override fun notifications(key: String, enable: Boolean) {}
        override fun disconnect() { disconnects++ }
    }
}
