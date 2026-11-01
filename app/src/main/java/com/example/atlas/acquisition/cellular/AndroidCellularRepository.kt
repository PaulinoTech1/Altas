package com.example.atlas.acquisition.cellular

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.telephony.*
import androidx.core.content.ContextCompat
import com.example.atlas.permissions.RadioPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class AndroidCellularRepository(context: Context, private val permissions: RadioPermissionManager) : CellularRepository {
    private val app = context.applicationContext
    private val base = app.getSystemService(TelephonyManager::class.java)
    private val subscriptions = app.getSystemService(SubscriptionManager::class.java)
    private val rate = CellUpdateRateGuard()

    private fun preflight() {
        check(app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) && base != null) { "Telephony hardware is unavailable." }
        check(Build.VERSION.SDK_INT < 33 || app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)) { "Public telephony radio access is unsupported." }
        check(permissions.missingForCellular().isEmpty()) { "Phone status and precise location permissions are required." }
        check(app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true) { "Location services are disabled; enable them manually before cellular collection." }
    }

    private fun active(): List<CellularSubscription> {
        try {
            return subscriptions?.activeSubscriptionInfoList.orEmpty().take(8).map {
                // Never retain SubscriptionInfo itself or read its protected subscriber fields.
                CellularSubscription(it.subscriptionId, it.simSlotIndex)
            }.sortedBy { it.slot }
        } catch (_: SecurityException) { throw SecurityException("Android denied active subscription access.") }
    }

    override fun observe(subscriptionId: Int?) = callbackFlow<CellularEvent> {
        preflight()
        val choices = active()
        val selected = if (subscriptionId != null) choices.firstOrNull { it.id == subscriptionId }
            ?: error("Selected subscription is no longer active. Review slots and restart.")
        else choices.firstOrNull { it.id == SubscriptionManager.getDefaultDataSubscriptionId() } ?: choices.firstOrNull()
        val manager = selected?.let { base!!.createForSubscriptionId(it.id) } ?: base!!
        val started = SystemClock.elapsedRealtime()
        fun deliver(create: () -> CellularEvent) {
            if (!this@callbackFlow.isActive) return
            try {
                preflight()
                trySend(create())
            } catch (_: SecurityException) { close(SecurityException("Android denied cellular callback access.")) }
            catch (failure: IllegalStateException) { close(failure) }
        }
        val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener,
            TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener, TelephonyCallback.DisplayInfoListener {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = deliver {
                CellularEvent.Cells(CellularMapper.batch(cellInfo, selected?.id, CellDelivery.CALLBACK, started))
            }
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = deliver { CellularEvent.Signal(CellularMapper.signal(signalStrength, selected?.id)) }
            override fun onServiceStateChanged(serviceState: ServiceState) = deliver { CellularEvent.Service(CellularMapper.service(serviceState)) }
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = deliver {
                CellularEvent.Display(DisplayReport(CellularValues.technology(telephonyDisplayInfo.networkType),
                    CellularValues.carrierHint(telephonyDisplayInfo.overrideNetworkType), System.currentTimeMillis()))
            }
        }
        val subscriptionListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                if (!this@callbackFlow.isActive) return
                try {
                    if (active() != choices) close(IllegalStateException("Active subscriptions changed. Collection stopped; review slots and restart."))
                } catch (_: SecurityException) { close(SecurityException("Subscription permission was revoked.")) }
            }
        }
        val locationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!this@callbackFlow.isActive) return
                try { preflight() } catch (failure: IllegalStateException) { close(failure) }
            }
        }
        var callbackRegistered = false
        var subscriptionRegistered = false
        var receiverRegistered = false
        fun cleanup() {
            try { if (callbackRegistered) manager.unregisterTelephonyCallback(callback) }
            finally {
                try { if (subscriptionRegistered) subscriptions?.removeOnSubscriptionsChangedListener(subscriptionListener) }
                finally { if (receiverRegistered) app.unregisterReceiver(locationReceiver) }
            }
        }
        try {
            trySend(CellularEvent.Session(choices, selected?.id, if (selected == null)
                "No active subscription reported. Only the device-wide cache is inspected; live subscription callbacks are unavailable."
                else "Selected subscription callback context. Live registration is requested for this slot; CellInfo carries no per-row SIM identifier."))
            ContextCompat.registerReceiver(app, locationReceiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            if (subscriptions != null) {
                subscriptions.addOnSubscriptionsChangedListener(app.mainExecutor, subscriptionListener)
                subscriptionRegistered = true
            }
            if (selected != null) {
                try { manager.registerTelephonyCallback(app.mainExecutor, callback); callbackRegistered = true }
                catch (_: SecurityException) { throw SecurityException("Android denied cellular listener registration.") }
            }
            val cached = try { manager.allCellInfo } catch (_: SecurityException) { throw SecurityException("Android denied cached cell information.") }
            if (cached != null) trySend(CellularEvent.Cells(CellularMapper.batch(cached, null, CellDelivery.DEVICE_CACHE, started)))
            else trySend(CellularEvent.Update(CellUpdateResult(CellRequestStatus.MODEM_ERROR, "Device-wide cell cache is unavailable (null); waiting for permitted reports.")))
            if (selected != null) {
                trySend(CellularEvent.Update(CellUpdateResult(CellRequestStatus.PENDING, "Requesting a permitted cell update; up to 15 seconds, no automatic retry.")))
                launch { trySend(CellularEvent.Update(requestUpdate(selected.id))) }
            }
        } catch (failure: Exception) {
            cleanup()
            throw failure
        }
        awaitClose {
            cleanup()
        }
    }.buffer(32, BufferOverflow.DROP_OLDEST).flowOn(Dispatchers.IO)

    override suspend fun requestUpdate(subscriptionId: Int): CellUpdateResult = withContext(Dispatchers.IO) {
        preflight()
        check(active().any { it.id == subscriptionId }) { "Selected subscription is no longer active." }
        val now = SystemClock.elapsedRealtime()
        val remaining = rate.reserve(now)
        if (remaining > 0) return@withContext CellUpdateResult(CellRequestStatus.LOCAL_COOLDOWN,
            "App update cooldown: wait ${(remaining + 999) / 1000}s. Android may rate-limit or return cached data; no automatic retry.")
        val manager = base!!.createForSubscriptionId(subscriptionId)
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                val callback = object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        if (!completed.compareAndSet(false, true) || !continuation.isActive) return
                        continuation.resume(CellUpdateResult(CellRequestStatus.DELIVERED,
                            "Android returned ${cellInfo.size} cells. Check source timestamps; a response is not proof of a new measurement.",
                            CellularMapper.batch(cellInfo, subscriptionId, CellDelivery.REQUEST_RESPONSE, now)))
                    }
                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (!completed.compareAndSet(false, true) || !continuation.isActive) return
                        continuation.resume(CellUpdateResult(if (errorCode == ERROR_TIMEOUT) CellRequestStatus.TIMED_OUT else CellRequestStatus.MODEM_ERROR,
                            "Cell update failed (platform code $errorCode); cached reports remain explicitly cached. No automatic retry."))
                    }
                }
                try { manager.requestCellInfoUpdate(app.mainExecutor, callback) }
                catch (_: SecurityException) { throw SecurityException("Android denied cell update access.") }
            }
        } ?: CellUpdateResult(CellRequestStatus.TIMED_OUT, "No cell update response within 15 seconds. Cached reports retained; no automatic retry.")
    }
}
