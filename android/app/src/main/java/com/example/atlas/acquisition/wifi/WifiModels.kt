package com.example.atlas.acquisition.wifi

import com.example.atlas.acquisition.bluetooth.hex
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class WifiDelivery { CACHE_READ, UPDATED_BROADCAST, NOT_UPDATED_BROADCAST }
enum class WifiFreshness(val label: String) { RECENT("Recent platform update"), CACHED("Cached"), STALE("Stale"), UNKNOWN("Source age unavailable") }

data class WifiObservation(
    val eventId: String,
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int?,
    val frequencyMhz: Int?,
    val channel: Int?,
    val channelWidth: String,
    val centerFrequency0Mhz: Int?,
    val centerFrequency1Mhz: Int?,
    val capabilities: String?,
    val observedAtMillis: Long,
    val receivedElapsedMicros: Long,
    val sourceElapsedMicros: Long?,
    val sessionStartedElapsedMicros: Long,
    val delivery: WifiDelivery,
    val sourceApi: String = "WifiManager.getScanResults / ScanResult"
) {
    fun ageMillis(nowElapsedMicros: Long): Long? = sourceElapsedMicros
        ?.takeIf { it > 0 && it <= nowElapsedMicros }?.let { (nowElapsedMicros - it) / 1000 }

    fun freshness(nowElapsedMicros: Long): WifiFreshness {
        val age = ageMillis(nowElapsedMicros) ?: return WifiFreshness.UNKNOWN
        return when {
            age > 30_000 -> WifiFreshness.STALE
            delivery == WifiDelivery.UPDATED_BROADCAST && sourceElapsedMicros?.let { it >= sessionStartedElapsedMicros } == true -> WifiFreshness.RECENT
            else -> WifiFreshness.CACHED
        }
    }
}

data class WifiConnection(
    val key: String, val ssid: String?, val bssid: String?, val rssiDbm: Int?,
    val frequencyMhz: Int?, val channel: Int?, val linkSpeedMbps: Int?,
    val txMbps: Int?, val rxMbps: Int?, val standard: Int?,
    val validated: Boolean, val observedAtMillis: Long
)

enum class WifiRequestStatus { ACCEPTED, LOCAL_RATE_LIMIT, OS_REJECTED }
data class WifiScanRequest(val status: WifiRequestStatus, val message: String)

sealed interface WifiEvent {
    data class Policy(val osScanThrottlingEnabled: Boolean?) : WifiEvent
    data class Results(val rows: List<WifiObservation>, val delivery: WifiDelivery, val totalRows: Int) : WifiEvent
    data class Connection(val value: WifiConnection) : WifiEvent
    data class ConnectionLost(val key: String) : WifiEvent
    data class Request(val value: WifiScanRequest) : WifiEvent
}

interface WifiSurveyRepository {
    fun survey(): Flow<WifiEvent>
    suspend fun requestScan(): WifiScanRequest
}

/** Application guard; it does not know Android's own scan budget. Uses boot-relative time. */
class WifiScanRateGuard {
    private val attempts = ArrayDeque<Long>()
    @Synchronized fun reserve(nowMillis: Long): Long {
        while (attempts.isNotEmpty() && nowMillis - attempts.first() >= 120_000) attempts.removeFirst()
        val spacing = attempts.lastOrNull()?.let { (it + 30_000 - nowMillis).coerceAtLeast(0) } ?: 0
        val budget = if (attempts.size >= 4) (attempts.first() + 120_000 - nowMillis).coerceAtLeast(0) else 0
        val remaining = maxOf(spacing, budget)
        if (remaining == 0L) attempts.addLast(nowMillis)
        return remaining
    }
}

class WifiHistoryStore(private val eventLimit: Int = 1024, private val addressLimit: Int = 256) {
    init { require(eventLimit > 0 && addressLimit > 0) }
    private val history = ArrayDeque<WifiObservation>()
    private val lastSource = linkedMapOf<String, Long>()
    var sampleCount = 0L
        private set

    fun add(rows: List<WifiObservation>) {
        rows.forEach { row ->
            // No invented identity or repeated cache samples when Android withholds identifiers/time.
            val address = row.bssid ?: return@forEach
            val source = row.sourceElapsedMicros ?: return@forEach
            val previous = lastSource[address] ?: history.lastOrNull { it.bssid == address }?.sourceElapsedMicros ?: 0
            if (row.ageMillis(row.receivedElapsedMicros) == null || source <= previous) return@forEach
            lastSource.remove(address)
            lastSource[address] = source
            if (lastSource.size > addressLimit) lastSource.remove(lastSource.keys.first())
            history.addLast(row)
            if (history.size > eventLimit) history.removeFirst()
            sampleCount++
        }
    }
    fun observations() = history.toList().asReversed()
    fun clear() { history.clear(); lastSource.clear(); sampleCount = 0 }
}

object WifiValues {
    fun channel(frequencyMhz: Int): Int? = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 && (frequencyMhz - 2412) % 5 == 0 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5160..5885 && frequencyMhz % 5 == 0 -> (frequencyMhz - 5000) / 5
        frequencyMhz == 5935 -> 2
        frequencyMhz in 5955..7115 && (frequencyMhz - 5955) % 5 == 0 -> (frequencyMhz - 5950) / 5
        frequencyMhz in 58320..69120 && (frequencyMhz - 58320) % 2160 == 0 -> (frequencyMhz - 56160) / 2160
        else -> null
    }
    fun band(frequencyMhz: Int?) = when (frequencyMhz) {
        in 2412..2484 -> "2.4 GHz"
        in 5160..5885 -> "5 GHz"
        5935, in 5955..7115 -> "6 GHz"
        in 58320..70200 -> "60 GHz"
        else -> "Unknown band"
    }
    fun ssid(value: String?): String? = value?.takeUnless { it.isEmpty() || it == "<unknown ssid>" }
        ?.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }?.take(128)?.takeIf { it.isNotBlank() }

    /** Marks SSID octets that are not valid UTF-8, so they are never mistaken for a printable network name. */
    const val NON_UTF8_SSID_PREFIX = "<non-UTF-8 SSID 0x"

    /** Raw SSID octets (API 33+ WifiSsid.getBytes()). Empty or all-zero octets are a hidden network: null, like other unavailable SSIDs. */
    fun ssidFromBytes(bytes: ByteArray?): String? {
        if (bytes == null || bytes.all { it == 0.toByte() }) return null
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { return "$NON_UTF8_SSID_PREFIX${bytes.hex()}>" }
        return ssid(text)
    }

    /** WifiInfo.getSSID(): quoted UTF-8 text, unquoted hex digits for non-UTF-8 octets, or "<unknown ssid>". */
    fun ssidFromPlatformText(value: String?): String? = when {
        value == null -> null
        value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> ssid(value.substring(1, value.length - 1))
        value.length in 2..64 && value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } ->
            ssidFromBytes(ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
        else -> ssid(value)
    }

    /** API 31/32 ScanResult.SSID text; keeps the historical surrounding-quote stripping. */
    fun legacySsid(value: String?): String? = ssid(value?.removeSurrounding("\""))
    fun bssid(value: String?): String? = value?.lowercase()?.takeIf {
        it.matches(Regex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")) && it !in setOf("02:00:00:00:00:00", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff")
    }
    fun rssi(value: Int): Int? = value.takeIf { it in -126..0 }
    fun positive(value: Int): Int? = value.takeIf { it > 0 }
    fun width(value: Int): String = when (value) {
        0 -> "20 MHz"; 1 -> "40 MHz"; 2 -> "80 MHz"; 3 -> "160 MHz"; 4 -> "80+80 MHz"; 5 -> "320 MHz"
        else -> "Unavailable / unsupported ($value)"
    }
    fun standard(value: Int?): String = when (value) {
        1 -> "Legacy Wi-Fi"; 4 -> "802.11n / Wi-Fi 4"; 5 -> "802.11ac / Wi-Fi 5"
        6 -> "802.11ax / Wi-Fi 6 or 6E"; 7 -> "802.11ad / WiGig"; 8 -> "802.11be / Wi-Fi 7"
        null, 0 -> "Unavailable"; else -> "Unrecognized standard ($value)"
    }
}
