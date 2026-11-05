package com.example.atlas.acquisition.wifi

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicInteger

/** A short, Wi-Fi-bound DNS-SD browse. This is a service snapshot, not a device inventory. */
class AndroidLanServiceBrowser(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)

    companion object {
        val serviceTypes = listOf("_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_airplay._tcp",
            "_googlecast._tcp", "_smb._tcp", "_workstation._tcp", "_http._tcp", "_ssh._tcp")
        private const val BROWSE_MILLIS = 6_000L
        private const val MAX_RESOLUTIONS = 64
    }

    /** Returns the number of service-type or resolution failures; callers should show partial results. */
    @Suppress("DEPRECATION")
    suspend fun browse(network: Network, target: LanTarget, onObservation: (LanServiceObservation) -> Unit): Int {
        if (Build.VERSION.SDK_INT < 33) return serviceTypes.size // Wi-Fi-bound NSD overload is unavailable.
        val allowed = target.hosts.toHashSet()
        val failures = AtomicInteger()
        val resolutions = AtomicInteger()
        // Android 13 devices without T extension 7 still need a foreground multicast lock for mDNS.
        val multicastLock = if (Build.VERSION.SDK_INT == 33) {
            val lockResult = runCatching {
                app.getSystemService(WifiManager::class.java).createMulticastLock("Atlas:LAN names").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (lockResult.isFailure) failures.incrementAndGet()
            lockResult.getOrNull()
        } else null
        val observations = callbackFlow {
            val listeners = mutableListOf<NsdManager.DiscoveryListener>()
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            for (type in serviceTypes) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        failures.incrementAndGet()
                        runCatching { nsd.stopServiceDiscovery(this) }
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        failures.incrementAndGet()
                    }
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (closed.get()) return
                        val count = resolutions.incrementAndGet()
                        if (count > MAX_RESOLUTIONS) {
                            if (count == MAX_RESOLUTIONS + 1) failures.incrementAndGet()
                            return
                        }
                        try {
                            nsd.resolveService(serviceInfo, app.mainExecutor, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                    failures.incrementAndGet()
                                }
                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    if (closed.get()) return
                                    val addresses = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses
                                        else listOfNotNull(resolved.host)
                                    for (address in addresses) {
                                        val ip = (address as? Inet4Address)?.hostAddress ?: continue
                                        if (ip !in allowed) continue
                                        val hostname = if (Build.VERSION.SDK_INT >= 36) resolved.hostname else null
                                        trySend(LanServiceObservation(ip, hostname,
                                            resolved.serviceName.orEmpty(), type))
                                    }
                                }
                            })
                        } catch (_: Exception) { failures.incrementAndGet() }
                    }
                }
                listeners.add(listener)
                try {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, network, app.mainExecutor, listener)
                } catch (_: Exception) {
                    listeners.remove(listener)
                    failures.incrementAndGet()
                }
            }
            awaitClose {
                closed.set(true)
                listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            }
        }
        try {
            try {
                withTimeout(BROWSE_MILLIS) { observations.collect(onObservation) }
            } catch (_: TimeoutCancellationException) { /* Expected end of the bounded browse. */ }
        } finally {
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
        return failures.get()
    }
}
