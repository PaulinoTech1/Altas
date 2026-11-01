package com.example.atlas.acquisition.cellular

import kotlinx.coroutines.flow.Flow

data class CellularSubscription(val id: Int, val slot: Int)
data class CellularMetric(val name: String, val value: Int?, val unit: String)
enum class CellDelivery { DEVICE_CACHE, CALLBACK, REQUEST_RESPONSE }

data class CellRecord(
    val rat: String, val registered: Boolean, val connectionStatus: Int,
    val cellId: Long?, val pci: Int?, val areaCode: Int?, val channel: Int?,
    val mcc: String?, val mnc: String?, val metrics: List<CellularMetric>,
    val sourceElapsedMillis: Long?
)

data class CellBatch(
    val eventId: String, val rows: List<CellRecord>, val totalRows: Int,
    val subscriptionContext: Int?, val delivery: CellDelivery,
    val receivedAtMillis: Long, val receivedElapsedMillis: Long,
    val referenceElapsedMillis: Long, val sourceApi: String
) {
    fun freshness(row: CellRecord, nowElapsedMillis: Long): String {
        val age = CellularValues.age(row.sourceElapsedMillis, nowElapsedMillis) ?: return "Source age unavailable"
        return when {
            age > 30_000 -> "Stale"
            delivery == CellDelivery.DEVICE_CACHE -> "Cached — device-wide"
            row.sourceElapsedMillis?.let { it >= referenceElapsedMillis } == true -> "Recent platform report"
            else -> "Cached source timestamp"
        }
    }
}

data class SubscriptionSignal(val rat: String, val metrics: List<CellularMetric>)
data class SignalReport(val signals: List<SubscriptionSignal>, val receivedAtMillis: Long,
    val receivedElapsedMillis: Long, val sourceElapsedMillis: Long?, val subscriptionContext: Int? = null)
data class RegistrationReport(val domain: String, val transport: String, val technology: String,
    val registered: Boolean, val searching: Boolean, val roaming: Boolean)
data class ServiceReport(val voiceState: String, val registrations: List<RegistrationReport>, val receivedAtMillis: Long)
data class DisplayReport(val technology: String, val carrierHint: String, val receivedAtMillis: Long)

enum class CellRequestStatus { PENDING, DELIVERED, LOCAL_COOLDOWN, TIMED_OUT, MODEM_ERROR, CANCELLED }
data class CellUpdateResult(val status: CellRequestStatus, val message: String, val batch: CellBatch? = null)

sealed interface CellularEvent {
    data class Session(val subscriptions: List<CellularSubscription>, val selectedId: Int?, val message: String) : CellularEvent
    data class Cells(val batch: CellBatch) : CellularEvent
    data class Signal(val report: SignalReport) : CellularEvent
    data class Service(val report: ServiceReport) : CellularEvent
    data class Display(val report: DisplayReport) : CellularEvent
    data class Update(val result: CellUpdateResult) : CellularEvent
}

interface CellularRepository {
    fun observe(subscriptionId: Int?): Flow<CellularEvent>
    suspend fun requestUpdate(subscriptionId: Int): CellUpdateResult
}

class CellUpdateRateGuard {
    private var last: Long? = null
    @Synchronized fun reserve(now: Long): Long {
        val remaining = last?.let { (it + 30_000 - now).coerceAtLeast(0) } ?: 0
        if (remaining == 0L) last = now
        return remaining
    }
}

object CellularValues {
    fun lte(rsrp: Int, rsrq: Int, rssnr: Int) = listOf(
        CellularMetric("LTE RSRP", metric(rsrp, -140..-43), "dBm"),
        CellularMetric("LTE RSRQ", metric(rsrq, -34..3), "dB"),
        CellularMetric("LTE RSSNR", metric(rssnr, -20..30), "dB"))
    fun nr(rsrp: Int, rsrq: Int, sinr: Int) = listOf(
        CellularMetric("NR SS-RSRP", metric(rsrp, -156..-31), "dBm"),
        CellularMetric("NR SS-RSRQ", metric(rsrq, -43..20), "dB"),
        CellularMetric("NR SS-SINR", metric(sinr, -23..40), "dB"))
    fun metric(value: Int, range: IntRange): Int? = value.takeIf { it != Int.MAX_VALUE && it in range }
    fun identifier(value: Int, maximum: Int = Int.MAX_VALUE - 1): Int? = value.takeIf { it in 0..maximum }
    fun identifier(value: Long, maximum: Long): Long? = value.takeIf { it != Long.MAX_VALUE && it in 0..maximum }
    fun age(source: Long?, now: Long): Long? = source?.takeIf { it > 0 && it <= now }?.let { now - it }
    fun technology(type: Int): String = when (type) {
        0 -> "Unknown"; 1 -> "GPRS"; 2 -> "EDGE"; 3 -> "UMTS"; 4 -> "CDMA"; 5 -> "EVDO 0"
        6 -> "EVDO A"; 7 -> "1xRTT"; 8 -> "HSDPA"; 9 -> "HSUPA"; 10 -> "HSPA"; 11 -> "iDEN"
        12 -> "EVDO B"; 13 -> "LTE"; 14 -> "eHRPD"; 15 -> "HSPA+"; 16 -> "GSM"
        17 -> "TD-SCDMA"; 18 -> "IWLAN"; 19 -> "LTE CA"; 20 -> "NR"
        else -> "Unrecognized technology ($type)"
    }
    fun carrierHint(type: Int): String = when (type) {
        0 -> "No override"; 1 -> "LTE carrier aggregation display"; 2 -> "LTE Advanced Pro display"
        3 -> "5G NSA display indicator"; 4 -> "5G NSA mmWave display indicator"; 5 -> "5G advanced display indicator"
        else -> "Unrecognized carrier display ($type)"
    }
    fun connection(status: Int, registered: Boolean): String = when (status) {
        1 -> "Primary serving (reported)"; 2 -> "Secondary serving (reported)"
        0 -> if (registered) "Registered; connection status none" else "Reported nonregistered / neighboring cell"
        else -> if (registered) "Registered; serving role unavailable" else "Unregistered; serving role unavailable"
    }
}
