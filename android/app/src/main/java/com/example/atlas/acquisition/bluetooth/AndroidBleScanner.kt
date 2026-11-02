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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.atlas.permissions.RadioPermissionManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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
        val scanSessionId = UUID.randomUUID().toString()
        val sensors = app.getSystemService(SensorManager::class.java)
        val orientationSensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val locations = app.getSystemService(LocationManager::class.java)
        val latestGps = AtomicReference<PhoneGpsFix?>()
        val gpsListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.provider != LocationManager.GPS_PROVIDER || !location.hasAccuracy()) return
                latestGps.set(PhoneGpsFix(location.latitude, location.longitude,
                    location.accuracy, location.elapsedRealtimeNanos))
            }
        }
        // Sensor events and BLE callbacks may arrive on different threads. A single immutable snapshot is enough.
        val latestOrientation = AtomicReference<PhoneOrientation?>()
        val orientationListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR || event.values.size < 3) return
                val matrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                if (matrix[2].isFinite() && matrix[5].isFinite() && matrix[8].isFinite())
                    latestOrientation.set(PhoneOrientation(matrix[2], matrix[5], matrix[8], event.timestamp))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = deliver(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::deliver) }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode (${scanFailure(errorCode)}). No automatic retry."))
            }

            private fun deliver(result: ScanResult) {
                try {
                    val source = result.timestampNanos.takeIf { it > 0 }
                    val received = SystemClock.elapsedRealtimeNanos()
                    val observationTime = source?.takeIf { it <= received } ?: received
                    val orientation = latestOrientation.get()?.takeIf {
                        observationTime >= it.elapsedNanos && observationTime - it.elapsedNanos <= 250_000_000L
                    }
                    val event = BleObservation(
                        UUID.randomUUID().toString(), result.device.address,
                        System.currentTimeMillis(), received, source,
                        result.rssi.takeUnless { it == 127 }, result.isConnectable,
                        AdvertisementParser.parse(result.scanRecord?.bytes,
                            result.dataStatus == ScanResult.DATA_TRUNCATED),
                        phoneOrientation = orientation,
                        orientationSessionId = scanSessionId,
                        phoneGpsFix = latestGps.get()?.takeIf { it.matches(observationTime) }
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
        var gpsRegistered = false
        lastStartElapsedMillis = now
        try {
            if (orientationSensor != null) sensors.registerListener(orientationListener, orientationSensor, SensorManager.SENSOR_DELAY_UI)
            try {
                if (locations?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                    locations.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(gpsListener::onLocationChanged)
                    locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f,
                        gpsListener, Looper.getMainLooper())
                    gpsRegistered = true
                }
            } catch (_: IllegalArgumentException) {
                // A missing GPS provider must not prevent BLE scanning.
            } catch (_: SecurityException) {
                // A GPS-specific denial must not prevent the existing BLE scan.
            }
            // Bluetooth broadcasts may originate from a separate privileged UID.
            ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            scanner.startScan(null, settings, callback)
        } catch (failure: Exception) {
            sensors?.unregisterListener(orientationListener)
            if (gpsRegistered) try { locations?.removeUpdates(gpsListener) } catch (_: SecurityException) { }
            if (registered) app.unregisterReceiver(receiver)
            // A partially registered scanner still needs cleanup before propagating failure.
            try { scanner.stopScan(callback) } catch (_: SecurityException) { /* OS revocation owns cleanup. */ }
            catch (_: IllegalStateException) { /* A disabled adapter cannot retain an active radio scan. */ }
            throw failure
        }
        awaitClose {
            sensors?.unregisterListener(orientationListener)
            if (gpsRegistered) try { locations?.removeUpdates(gpsListener) } catch (_: SecurityException) { }
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
