package com.example.atlas.acquisition.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class LanTarget(val localAddress: String, val prefixLength: Int, val hosts: List<String>,
                     val gatewayAddress: String? = null)
data class LanHost(val address: String, val respondingPorts: List<Int>)
data class LanProgress(val checked: Int, val total: Int, val hosts: List<LanHost>, val probeErrors: Int = 0)
data class LanConnection(val target: LanTarget, val probe: LanProbe, val network: Network)

object LanDiscoveryPolicy {
    val ports = listOf(80, 443, 22, 445, 8080)
    const val workers = 16
    const val timeoutMillis = 350

    /** Limit active probes to a directly connected private IPv4 subnet of at most 256 addresses. */
    fun target(address: Inet4Address, prefix: Int): LanTarget? {
        if (prefix !in 24..30) return null
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes[0] != 10 && !(bytes[0] == 172 && bytes[1] in 16..31) &&
            !(bytes[0] == 192 && bytes[1] == 168)) return null
        val value = bytes.fold(0L) { n, octet -> (n shl 8) or octet.toLong() }
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val base = value and mask
        val count = 1 shl (32 - prefix)
        val hosts = (1 until count - 1).map { offset -> base + offset }
            .filter { it != value }
            .map { n -> listOf(24, 16, 8, 0).joinToString(".") { ((n shr it) and 255).toString() } }
        return LanTarget(address.hostAddress ?: return null, prefix, hosts)
    }
}

enum class LanProbeResult { CONNECTED, NO_CONNECTION, TIMEOUT, ERROR }
fun interface LanProbe { fun connect(address: String, port: Int, timeoutMillis: Int): LanProbeResult }

class AndroidLanDiscovery(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    suspend fun connection(): LanConnection {
        val pair = withTimeoutOrNull(5_000) {
            callbackFlow<Pair<Network, LinkProperties>> {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                        trySend(network to properties)
                    }
                }
                try {
                    connectivity.registerNetworkCallback(NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
                } catch (failure: Exception) { close(failure) }
                awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
            }.first { (_, properties) -> properties.linkAddresses.any { link ->
                (link.address as? Inet4Address)?.let { LanDiscoveryPolicy.target(it, link.prefixLength) } != null
            } }
        } ?: throw IllegalStateException("No eligible private Wi-Fi IPv4 /24 to /30 connection was reported.")
        val subnet = pair.second.linkAddresses.firstNotNullOfOrNull { link ->
            (link.address as? Inet4Address)?.let { LanDiscoveryPolicy.target(it, link.prefixLength) }
        } ?: throw IllegalStateException("Wi-Fi needs a private IPv4 /24 to /30 address for bounded discovery.")
        val gateway = pair.second.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        val target = subnet.copy(gatewayAddress = gateway?.takeIf { it in subnet.hosts })
        val probe = LanProbe { host, port, timeout ->
            try {
                pair.first.socketFactory.createSocket().use {
                    it.connect(InetSocketAddress(host, port), timeout)
                }
                LanProbeResult.CONNECTED
            } catch (_: SocketTimeoutException) { LanProbeResult.TIMEOUT }
            catch (_: ConnectException) { LanProbeResult.NO_CONNECTION }
            catch (_: IOException) { LanProbeResult.ERROR }
        }
        return LanConnection(target, probe, pair.first)
    }
}

class LanDiscovery(private val probe: LanProbe) {
    suspend fun scan(target: LanTarget, onProgress: (LanProgress) -> Unit): LanProgress {
        val next = AtomicInteger()
        val checked = AtomicInteger()
        val probeErrors = AtomicInteger()
        val found = java.util.concurrent.ConcurrentHashMap<String, LanHost>()
        coroutineScope {
            repeat(minOf(LanDiscoveryPolicy.workers, target.hosts.size)) {
                launch(Dispatchers.IO) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val index = next.getAndIncrement()
                        if (index >= target.hosts.size) break
                        val host = target.hosts[index]
                        val ports = mutableListOf<Int>()
                        for (port in LanDiscoveryPolicy.ports) {
                            coroutineContext.ensureActive()
                            when (probe.connect(host, port, LanDiscoveryPolicy.timeoutMillis)) {
                                LanProbeResult.CONNECTED -> ports.add(port)
                                LanProbeResult.ERROR -> probeErrors.incrementAndGet()
                                LanProbeResult.NO_CONNECTION, LanProbeResult.TIMEOUT -> Unit
                            }
                        }
                        if (ports.isNotEmpty()) found[host] = LanHost(host, ports)
                        val count = checked.incrementAndGet()
                        if (count % 16 == 0 || count == target.hosts.size)
                            onProgress(LanProgress(count, target.hosts.size,
                                found.values.sortedWith(compareBy { ipValue(it.address) }), probeErrors.get()))
                    }
                }
            }
        }
        return LanProgress(checked.get(), target.hosts.size,
            found.values.sortedWith(compareBy { ipValue(it.address) }), probeErrors.get())
    }

    private fun ipValue(address: String): Long = address.split('.').fold(0L) { n, part -> (n shl 8) or part.toLong() }
}
