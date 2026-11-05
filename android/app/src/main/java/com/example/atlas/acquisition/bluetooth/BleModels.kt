package com.example.atlas.acquisition.bluetooth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class BleScanMode(val label: String) {
    LOW_POWER("Low power"), BALANCED("Balanced"), LOW_LATENCY("High intensity")
}

data class BleScanConfig(val mode: BleScanMode = BleScanMode.BALANCED, val seconds: Int = 10) {
    init { require(seconds in listOf(10, 20, 30)) }
}

data class Advertisement(
    val name: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerData: List<ManufacturerData> = emptyList(),
    val txPowerDbm: Int? = null,
    val rawHex: String = "",
    val malformed: Boolean = false,
    val truncated: Boolean = false
)

data class ManufacturerData(val companyId: Int, val payloadHex: String)

/** Phone-facing direction in a session-relative frame. It is not a bearing to a BLE transmitter. */
data class PhoneOrientation(val x: Float, val y: Float, val z: Float, val elapsedNanos: Long)

data class BleObservation(
    val eventId: String,
    val address: String,
    val observedAtMillis: Long,
    val receivedElapsedNanos: Long,
    val sourceElapsedNanos: Long?,
    val rssiDbm: Int?,
    val connectable: Boolean,
    val advertisement: Advertisement,
    val sourceApi: String = "BluetoothLeScanner / ScanResult",
    val phoneOrientation: PhoneOrientation? = null,
    val orientationSessionId: String? = null,
    /** Callback order within this scan; gaps in retained observations reveal lost samples. */
    val scanSequence: Long? = null,
    val resultTxPowerDbm: Int? = null,
    val primaryPhy: Int? = null,
    val advertisingSid: Int? = null
) {
    fun ageMillis(nowElapsedNanos: Long): Long? = sourceElapsedNanos?.takeIf { it <= nowElapsedNanos }?.let {
        (nowElapsedNanos - it) / 1_000_000
    }
}

data class BleDeviceHistory(
    val latest: BleObservation,
    val firstSeenMillis: Long,
    val receivedCount: Long
)

enum class BleDeviceSort(val label: String) {
    RECENT("Recent"), STRONGEST("Strongest RSSI"), MOST_SEEN("Most seen"), NAME("Name");

    fun order(devices: List<BleDeviceHistory>): List<BleDeviceHistory> = when (this) {
        RECENT -> devices.sortedWith(compareByDescending<BleDeviceHistory> { it.latest.observedAtMillis }
            .thenBy { it.latest.address })
        STRONGEST -> devices.sortedWith(compareByDescending<BleDeviceHistory> { it.latest.rssiDbm ?: Int.MIN_VALUE }
            .thenByDescending { it.latest.observedAtMillis }.thenBy { it.latest.address })
        MOST_SEEN -> devices.sortedWith(compareByDescending<BleDeviceHistory> { it.receivedCount }
            .thenByDescending { it.latest.observedAtMillis }.thenBy { it.latest.address })
        NAME -> devices.sortedWith(compareBy<BleDeviceHistory> {
            it.latest.advertisement.name?.takeIf(String::isNotBlank)?.lowercase() ?: "\uffff"
        }.thenBy { it.latest.address })
    }
}

/** Identity is the currently observed address, which can rotate. No physical identity inference. */
class BleHistoryStore(private val deviceLimit: Int = 256, private val eventLimit: Int = 1024) {
    init { require(deviceLimit > 0 && eventLimit > 0) }
    private val devices = linkedMapOf<String, BleDeviceHistory>()
    private val history = ArrayDeque<BleObservation>()
    var receivedCount = 0L
        private set

    fun add(event: BleObservation) {
        val old = devices.remove(event.address)
        devices[event.address] = BleDeviceHistory(event, old?.firstSeenMillis ?: event.observedAtMillis,
            (old?.receivedCount ?: 0) + 1)
        if (devices.size > deviceLimit) devices.remove(devices.keys.first())
        history.addLast(event)
        if (history.size > eventLimit) history.removeFirst()
        receivedCount++
    }

    fun devices(): List<BleDeviceHistory> = devices.values.sortedWith(
        compareByDescending<BleDeviceHistory> { it.firstSeenMillis }.thenBy { it.latest.address })
    fun observations(): List<BleObservation> = history.toList().asReversed()
    fun clear() { devices.clear(); history.clear(); receivedCount = 0 }
}

interface BleScanner {
    fun scan(config: BleScanConfig): Flow<BleObservation>
}

data class GattCharacteristicInfo(
    val key: String, val serviceUuid: String, val uuid: String,
    val readable: Boolean, val notifiable: Boolean, val indicatable: Boolean
)

data class GattValue(val key: String, val hex: String, val timestampMillis: Long, val kind: String)

data class GattState(
    val address: String? = null,
    val status: String = "Disconnected",
    val ready: Boolean = false,
    val busy: Boolean = false,
    val serviceUuids: List<String> = emptyList(),
    val characteristics: List<GattCharacteristicInfo> = emptyList(),
    val subscribed: Set<String> = emptySet(),
    val values: List<GattValue> = emptyList(),
    val error: String? = null,
    val truncated: Boolean = false
)

interface GattClient {
    val state: StateFlow<GattState>
    fun connect(address: String)
    fun read(key: String)
    fun notifications(key: String, enable: Boolean)
    fun disconnect()
}

fun ByteArray.hex(): String {
    val digits = "0123456789ABCDEF"
    val result = CharArray(size * 2)
    indices.forEach { index ->
        val value = this[index].toInt() and 0xff
        result[index * 2] = digits[value ushr 4]
        result[index * 2 + 1] = digits[value and 15]
    }
    return result.concatToString()
}
