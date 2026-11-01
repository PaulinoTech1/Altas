package com.example.atlas.acquisition.bluetooth

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.atlas.permissions.RadioPermissionManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

class AndroidBleScanner(context: Context, private val permissions: RadioPermissionManager) : BleScanner {
    private val app = context.applicationContext
    private var lastStartElapsedMillis: Long? = null

    override fun scan(config: BleScanConfig): Flow<BleObservation> = callbackFlow {
        check(app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) { "BLE hardware is unavailable." }
        check(permissions.missingForBleScan().isEmpty()) { "Nearby devices and precise location permissions are required for RF surveying." }
        check(app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true) { "Location services are disabled; enable them manually before surveying." }
        val now = SystemClock.elapsedRealtime()
        val remaining = lastStartElapsedMillis?.let { 10_000 - (now - it) } ?: 0
        check(remaining <= 0) { "Scan restart cooldown: wait ${(remaining + 999) / 1000} seconds. Android scan policies still apply." }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        check(adapter?.isEnabled == true) { "Bluetooth is disabled or unavailable. Enable it manually." }
        val scanner: BluetoothLeScanner = checkNotNull(adapter.bluetoothLeScanner) { "BLE scanner is unavailable." }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = deliver(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::deliver) }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode (${scanFailure(errorCode)}). No automatic retry."))
            }

            private fun deliver(result: ScanResult) {
                try {
                    val source = result.timestampNanos.takeIf { it > 0 }
                    val event = BleObservation(
                        UUID.randomUUID().toString(), result.device.address,
                        System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), source,
                        result.rssi.takeUnless { it == 127 }, result.isConnectable,
                        AdvertisementParser.parse(result.scanRecord?.bytes,
                            result.dataStatus == ScanResult.DATA_TRUNCATED)
                    )
                    trySend(event)
                } catch (_: SecurityException) {
                    close(SecurityException("Bluetooth permission was revoked during scanning."))
                }
            }
        }
        val mode = when (config.mode) {
            BleScanMode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
            BleScanMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
            BleScanMode.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
        }
        val settings = ScanSettings.Builder().setScanMode(mode).setReportDelay(0).setLegacy(false).build()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Re-read trusted services, never trust a broadcast's claimed hardware state.
                try {
                    if (!adapter.isEnabled) close(IllegalStateException("Bluetooth was disabled during the scan."))
                    else if (app.getSystemService(LocationManager::class.java)?.isLocationEnabled != true)
                        close(IllegalStateException("Location services were disabled during the scan."))
                } catch (_: SecurityException) { close(SecurityException("Bluetooth access was revoked.")) }
            }
        }
        var registered = false
        lastStartElapsedMillis = now
        try {
            // Bluetooth broadcasts may originate from a separate privileged UID.
            ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            scanner.startScan(null, settings, callback)
        } catch (failure: Exception) {
            if (registered) app.unregisterReceiver(receiver)
            // A partially registered scanner still needs cleanup before propagating failure.
            try { scanner.stopScan(callback) } catch (_: SecurityException) { /* OS revocation owns cleanup. */ }
            catch (_: IllegalStateException) { /* A disabled adapter cannot retain an active radio scan. */ }
            throw failure
        }
        awaitClose {
            if (registered) app.unregisterReceiver(receiver)
            try { scanner.stopScan(callback) } catch (_: SecurityException) { /* OS revocation prevents app cleanup. */ }
            catch (_: IllegalStateException) { /* Bluetooth was disabled; no active radio scan remains. */ }
        }
    }.buffer(64, BufferOverflow.DROP_OLDEST)

    private fun scanFailure(code: Int): String = when (code) {
        1 -> "already started"; 2 -> "registration failed"; 3 -> "internal error"
        4 -> "feature unsupported"; 5 -> "out of hardware resources"; 6 -> "scanning too frequently"
        else -> "platform error"
    }
}
