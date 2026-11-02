package com.example.atlas.acquisition.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.atlas.permissions.RadioPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.UUID

class AndroidWifiSurveyRepository(context: Context, private val permissions: RadioPermissionManager) : WifiSurveyRepository {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val rateGuard = WifiScanRateGuard()

    private fun preflight() {
        check(app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) { "Wi-Fi hardware is unavailable." }
        check(permissions.missingForWifiSurvey().isEmpty()) { "Precise location permission is required for Wi-Fi surveying." }
        check(app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true) { "Location services are disabled. Enable them manually before surveying." }
        check(wifi?.isWifiEnabled == true) { "Wi-Fi is disabled or unavailable. Enable it manually before surveying." }
    }

    // startScan is deprecated but remains the public full-survey request API; never bypass OS limits.
    @Suppress("DEPRECATION")
    override suspend fun requestScan(): WifiScanRequest = withContext(Dispatchers.IO) {
        preflight()
        val waitMillis = rateGuard.reserve(SystemClock.elapsedRealtime())
        if (waitMillis > 0) return@withContext WifiScanRequest(WifiRequestStatus.LOCAL_RATE_LIMIT,
            "App scan cooldown: wait ${(waitMillis + 999) / 1000}s. Maximum four requests per rolling two minutes; Android may impose stricter limits.")
        try {
            if (wifi!!.startScan()) WifiScanRequest(WifiRequestStatus.ACCEPTED, "Scan request accepted; waiting for a shared platform update. Results can still be cached.")
            else WifiScanRequest(WifiRequestStatus.OS_REJECTED, "Android rejected the scan request. Throttling, idle state or hardware conditions may be responsible; no automatic retry.")
        } catch (_: SecurityException) { throw SecurityException("Android denied the Wi-Fi scan request; review precise location access.") }
    }

    override fun survey() = callbackFlow<WifiEvent> {
        preflight()
        val started = SystemClock.elapsedRealtimeNanos() / 1000
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!this@callbackFlow.isActive) return
                launch { // Binder/cache processing stays off the main broadcast thread.
                try {
                    preflight()
                    if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                        val delivery = if (intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                            WifiDelivery.UPDATED_BROADCAST else WifiDelivery.NOT_UPDATED_BROADCAST
                        trySend(results(delivery, started))
                    }
                } catch (_: SecurityException) { close(SecurityException("Wi-Fi survey access was revoked.")) }
                catch (failure: IllegalStateException) { close(failure) }
                }
            }
        }
        val networkCallback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!this@callbackFlow.isActive) return
                try {
                    preflight()
                    val info = networkCapabilities.transportInfo as? WifiInfo ?: return
                    trySend(WifiEvent.Connection(WifiConnection(
                        network.networkHandle.toString(), WifiValues.ssidFromPlatformText(info.ssid),
                        WifiValues.bssid(info.bssid), WifiValues.rssi(info.rssi), WifiValues.positive(info.frequency),
                        WifiValues.channel(info.frequency), WifiValues.positive(info.linkSpeed),
                        WifiValues.positive(info.txLinkSpeedMbps), WifiValues.positive(info.rxLinkSpeedMbps),
                        info.wifiStandard.takeIf { it > 0 }, networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        System.currentTimeMillis()
                    )))
                } catch (_: SecurityException) { close(SecurityException("Android denied Wi-Fi connection information.")) }
                catch (failure: IllegalStateException) { close(failure) }
            }
            override fun onLost(network: Network) { trySend(WifiEvent.ConnectionLost(network.networkHandle.toString())) }
        }
        var receiverRegistered = false
        var callbackRegistered = false
        try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            connectivity?.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
            callbackRegistered = connectivity != null
            val throttling = try { wifi?.isScanThrottleEnabled } catch (_: SecurityException) { null }
                catch (_: UnsupportedOperationException) { null }
            trySend(WifiEvent.Policy(throttling))
            trySend(results(WifiDelivery.CACHE_READ, started))
            trySend(WifiEvent.Request(requestScan()))
        } catch (failure: Exception) {
            if (receiverRegistered) app.unregisterReceiver(receiver)
            if (callbackRegistered) connectivity?.unregisterNetworkCallback(networkCallback)
            throw failure
        }
        awaitClose {
            if (receiverRegistered) app.unregisterReceiver(receiver)
            if (callbackRegistered) connectivity?.unregisterNetworkCallback(networkCallback)
        }
    }.buffer(32, BufferOverflow.DROP_OLDEST).flowOn(Dispatchers.IO)

    private fun results(delivery: WifiDelivery, sessionStarted: Long): WifiEvent.Results {
        try {
            val source = wifi!!.scanResults
            val now = SystemClock.elapsedRealtimeNanos() / 1000
            val wall = System.currentTimeMillis()
            val rows = source.take(256).map { result -> WifiObservation(
                UUID.randomUUID().toString(), scanSsid(result), WifiValues.bssid(result.BSSID),
                WifiValues.rssi(result.level), WifiValues.positive(result.frequency), WifiValues.channel(result.frequency),
                WifiValues.width(result.channelWidth), WifiValues.positive(result.centerFreq0), WifiValues.positive(result.centerFreq1),
                result.capabilities?.take(2048), wall, now, result.timestamp.takeIf { it > 0 }, sessionStarted, delivery
            ) }.sortedBy { it.bssid ?: it.ssid ?: "" }
            return WifiEvent.Results(rows, delivery, source.size)
        } catch (_: SecurityException) { throw SecurityException("Android denied cached Wi-Fi results; review location access.") }
    }

    @Suppress("DEPRECATION") // API 31/32 do not expose ScanResult.getWifiSsid().
    private fun scanSsid(result: ScanResult): String? =
        // WifiSsid.toString() quotes UTF-8 names and gives bare hex otherwise; decode the octets instead.
        if (Build.VERSION.SDK_INT >= 33) WifiValues.ssidFromBytes(result.wifiSsid?.bytes) else WifiValues.legacySsid(result.SSID)
}
