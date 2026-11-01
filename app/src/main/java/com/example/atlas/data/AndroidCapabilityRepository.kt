package com.example.atlas.data

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcManager
import com.example.atlas.model.Availability
import com.example.atlas.model.PermissionState
import com.example.atlas.model.RadioCapability
import com.example.atlas.model.RadioState
import com.example.atlas.model.RadioType
import com.example.atlas.permissions.RadioPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidCapabilityRepository(
    context: Context,
    private val permissions: RadioPermissionManager
) : CapabilityRepository {
    private val app = context.applicationContext
    private val pm = app.packageManager

    override suspend fun snapshot(): List<RadioCapability> = withContext(Dispatchers.IO) {
        RadioType.entries.map { type ->
            ensureActive()
            inspect(type)
        }
    }

    private fun inspect(type: RadioType): RadioCapability {
        var base = RadioCapability(
            type = type, permission = permissions.state(type),
            checkedAtEpochMillis = System.currentTimeMillis()
        )
        return try {
            val feature = when (type) {
                RadioType.BLUETOOTH -> PackageManager.FEATURE_BLUETOOTH
                RadioType.NFC -> PackageManager.FEATURE_NFC
                RadioType.WIFI -> PackageManager.FEATURE_WIFI
                RadioType.CELLULAR -> PackageManager.FEATURE_TELEPHONY
            }
            val present = pm.hasSystemFeature(feature) ||
                (type == RadioType.BLUETOOTH && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            if (!present) return base.copy(
                availability = Availability.UNAVAILABLE, state = RadioState.UNAVAILABLE,
                detail = "Android does not report the $feature feature."
            )
            base = base.copy(availability = Availability.AVAILABLE)
            val available = base
            when (type) {
                RadioType.BLUETOOTH -> {
                    val ble = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                    if (base.permission == PermissionState.REQUIRED) available.copy(
                        state = RadioState.PERMISSION_REQUIRED,
                        detail = "Bluetooth reported; BLE ${if (ble) "reported" else "not reported"}. Allow Nearby devices to check adapter state."
                    ) else {
                        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
                        available.copy(
                            state = adapter?.let { enabledState(it.isEnabled) } ?: RadioState.ERROR,
                            detail = "BLE ${if (ble) "reported" else "not reported"}. Open Bluetooth for user-controlled scanning and GATT.",
                            sourceApi = "PackageManager / BluetoothAdapter.isEnabled",
                            error = if (adapter == null) "Bluetooth adapter unavailable despite feature declaration" else null
                        )
                    }
                }
                RadioType.NFC -> {
                    val adapter = app.getSystemService(NfcManager::class.java)?.defaultAdapter
                    available.copy(
                        state = adapter?.let { enabledState(it.isEnabled) } ?: RadioState.ERROR,
                        detail = "Reader mode inactive. Tag reading and APDU operations arrive in Phase 5.",
                        sourceApi = "PackageManager / NfcAdapter.isEnabled",
                        error = if (adapter == null) "NFC adapter unavailable despite feature declaration" else null
                    )
                }
                RadioType.WIFI -> {
                    val manager = app.getSystemService(WifiManager::class.java)
                    available.copy(
                        state = manager?.let { enabledState(it.isWifiEnabled) } ?: RadioState.ERROR,
                        detail = "No network scan requested or cached results read. Surveying arrives in Phase 3.",
                        sourceApi = "PackageManager / WifiManager.isWifiEnabled",
                        error = if (manager == null) "Wi-Fi service unavailable" else null
                    )
                }
                RadioType.CELLULAR -> {
                    val manager = app.getSystemService(ConnectivityManager::class.java)
                    val caps = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
                    val transport = when {
                        manager == null -> "Connectivity service unavailable."
                        caps == null -> "No active network capabilities reported."
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Active default network reports cellular transport."
                        else -> "Active default network does not report cellular transport."
                    }
                    available.copy(
                        state = RadioState.NOT_QUERIED,
                        detail = "$transport Modem power, registration, SIMs and LTE/NR metrics are not queried in Phase 1.",
                        sourceApi = "PackageManager / ConnectivityManager.getNetworkCapabilities"
                    )
                }
            }
        } catch (_: SecurityException) {
            base.copy(state = RadioState.PERMISSION_REQUIRED,
                error = "Android denied state access. Refresh permissions or open app settings.")
        } catch (_: UnsupportedOperationException) {
            base.copy(state = RadioState.ERROR, error = "State API unsupported by this device.")
        } catch (_: IllegalStateException) {
            base.copy(state = RadioState.ERROR, error = "Radio service temporarily unavailable; refresh to retry.")
        }
    }

    private fun enabledState(enabled: Boolean) = if (enabled) RadioState.ENABLED else RadioState.DISABLED
}
