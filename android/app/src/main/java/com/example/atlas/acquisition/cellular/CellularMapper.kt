package com.example.atlas.acquisition.cellular

import android.os.Build
import android.os.SystemClock
import android.telephony.*
import java.util.UUID

object CellularMapper {
    fun batch(cells: List<CellInfo>, subscription: Int?, delivery: CellDelivery, reference: Long): CellBatch = CellBatch(
        UUID.randomUUID().toString(), cells.take(64).map(::cell), cells.size, subscription, delivery,
        System.currentTimeMillis(), SystemClock.elapsedRealtime(), reference,
        when (delivery) { CellDelivery.DEVICE_CACHE -> "TelephonyManager.getAllCellInfo"
            CellDelivery.CALLBACK -> "TelephonyCallback.CellInfoListener"
            CellDelivery.REQUEST_RESPONSE -> "TelephonyManager.requestCellInfoUpdate" }
    )

    fun cell(info: CellInfo): CellRecord {
        val identity = info.cellIdentity
        val lte = identity as? CellIdentityLte
        val nr = identity as? CellIdentityNr
        val rat = when (info) {
            is CellInfoLte -> "LTE"; is CellInfoNr -> "NR"; is CellInfoGsm -> "GSM"
            is CellInfoWcdma -> "WCDMA"; is CellInfoTdscdma -> "TD-SCDMA"; else -> "Other (${info.javaClass.simpleName.take(40)})"
        }
        return CellRecord(rat, info.isRegistered, info.cellConnectionStatus,
            lte?.let { CellularValues.identifier(it.ci, 0x0fffffff)?.toLong() }
                ?: nr?.let { CellularValues.identifier(it.nci, 0xfffffffffL) },
            lte?.let { CellularValues.identifier(it.pci, 503) } ?: nr?.let { CellularValues.identifier(it.pci, 1007) },
            lte?.let { CellularValues.identifier(it.tac, 65535) } ?: nr?.let { CellularValues.identifier(it.tac, 0xffffff) },
            lte?.let { CellularValues.identifier(it.earfcn) } ?: nr?.let { CellularValues.identifier(it.nrarfcn) },
            lte?.mccString ?: nr?.mccString, lte?.mncString ?: nr?.mncString,
            metrics(info.cellSignalStrength), info.timestampMillis.takeIf { it > 0 && it != Long.MAX_VALUE })
    }

    fun metrics(value: CellSignalStrength): List<CellularMetric> = when (value) {
        is CellSignalStrengthLte -> CellularValues.lte(value.rsrp, value.rsrq, value.rssnr)
        is CellSignalStrengthNr -> CellularValues.nr(value.ssRsrp, value.ssRsrq, value.ssSinr)
        else -> listOf(CellularMetric("Reported signal", CellularValues.metric(value.dbm, -200..0), "dBm"))
    }

    fun signal(value: SignalStrength, subscription: Int?): SignalReport = SignalReport(value.cellSignalStrengths.take(8).map {
        SubscriptionSignal(when (it) { is CellSignalStrengthLte -> "LTE"; is CellSignalStrengthNr -> "NR"; else -> it.javaClass.simpleName.removePrefix("CellSignalStrength") }, metrics(it))
    }, System.currentTimeMillis(), SystemClock.elapsedRealtime(), value.timestampMillis.takeIf { it > 0 && it != Long.MAX_VALUE }, subscription)

    fun service(value: ServiceState): ServiceReport = ServiceReport(
        when (value.state) { 0 -> "In service"; 1 -> "Out of service"; 2 -> "Emergency only"; 3 -> "Radio powered off"; else -> "Unknown" },
        value.networkRegistrationInfoList.take(8).map {
            RegistrationReport(when (it.domain) { NetworkRegistrationInfo.DOMAIN_CS -> "CS"; NetworkRegistrationInfo.DOMAIN_PS -> "PS"; NetworkRegistrationInfo.DOMAIN_CS_PS -> "CS+PS"; else -> "Unknown" },
                when (it.transportType) { AccessNetworkConstants.TRANSPORT_TYPE_WWAN -> "WWAN (cellular)"; AccessNetworkConstants.TRANSPORT_TYPE_WLAN -> "WLAN"; else -> "Unknown" },
                CellularValues.technology(it.accessNetworkTechnology), registered(it), searching(it), roaming(it))
        }, System.currentTimeMillis())

    @Suppress("DEPRECATION") // API 31–33 registration booleans can be affected by carrier overrides.
    private fun registered(info: NetworkRegistrationInfo) = if (Build.VERSION.SDK_INT >= 34) info.isNetworkRegistered else info.isRegistered
    @Suppress("DEPRECATION")
    private fun searching(info: NetworkRegistrationInfo) = if (Build.VERSION.SDK_INT >= 34) info.isNetworkSearching else info.isSearching
    @Suppress("DEPRECATION")
    private fun roaming(info: NetworkRegistrationInfo) = if (Build.VERSION.SDK_INT >= 34) info.isNetworkRoaming else info.isRoaming
}
