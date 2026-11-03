package com.example.atlas.acquisition.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class TcpScanRequest(val host: String, val firstPort: Int, val lastPort: Int)
data class TcpScanProgress(val tested: Int, val total: Int, val openPorts: List<Int>,
                           val noConnection: Int, val timedOut: Int, val errors: Int)

object TcpScanPolicy {
    const val MAX_PORTS = 4096
    const val WORKERS = 16
    const val TIMEOUT_MILLIS = 450

    /** Numeric IPv4 only: no DNS lookup, implicit subnet expansion, or URL parsing. */
    fun validate(request: TcpScanRequest): String? {
        val octets = request.host.trim().split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } ||
                    it.toIntOrNull() !in 0..255 }) return "Enter one numeric IPv4 address."
        if (request.firstPort !in 1..65535 || request.lastPort !in request.firstPort..65535)
            return "Enter a valid TCP port range from 1 to 65535."
        if (request.lastPort - request.firstPort + 1 > MAX_PORTS)
            return "Limit each scan to $MAX_PORTS ports."
        return null
    }
}

fun interface TcpProbe { fun connect(address: InetSocketAddress, timeoutMillis: Int): TcpProbeResult }
enum class TcpProbeResult { OPEN, NO_CONNECTION, TIMEOUT, ERROR }

class AndroidWifiTcpProbe(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    suspend fun wifiProbe(): TcpProbe {
        val network = withTimeoutOrNull(2_000) {
            callbackFlow<Network> {
                val callback = object : NetworkCallback() {
                    override fun onAvailable(network: Network) { trySend(network) }
                }
                try {
                    connectivity.registerNetworkCallback(NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
                } catch (failure: Exception) { close(failure) }
                awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
            }.first()
        } ?: throw IllegalStateException("No Wi-Fi network is available. Connect to Wi-Fi before scanning.")
        return TcpProbe { address, timeout ->
            try {
                network.socketFactory.createSocket().use { it.connect(address, timeout) }
                TcpProbeResult.OPEN
            } catch (_: SocketTimeoutException) { TcpProbeResult.TIMEOUT }
            catch (_: ConnectException) { TcpProbeResult.NO_CONNECTION }
            catch (_: IOException) { TcpProbeResult.ERROR }
            catch (_: SecurityException) { TcpProbeResult.ERROR }
        }
    }
}

class TcpPortScanner(private val probe: TcpProbe) {
    suspend fun scan(request: TcpScanRequest, onProgress: (TcpScanProgress) -> Unit): TcpScanProgress {
        require(TcpScanPolicy.validate(request) == null)
        val address = InetAddress.getByAddress(request.host.trim().split('.').map { it.toInt().toByte() }.toByteArray())
        val total = request.lastPort - request.firstPort + 1
        val next = AtomicInteger(request.firstPort)
        val tested = AtomicInteger()
        val noConnection = AtomicInteger()
        val timedOut = AtomicInteger()
        val errors = AtomicInteger()
        val open = java.util.concurrent.ConcurrentSkipListSet<Int>()
        supervisorScope {
            repeat(minOf(TcpScanPolicy.WORKERS, total)) {
                launch(Dispatchers.IO) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val port = next.getAndIncrement()
                        if (port > request.lastPort) break
                        val result = try { probe.connect(InetSocketAddress(address, port), TcpScanPolicy.TIMEOUT_MILLIS) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { TcpProbeResult.ERROR }
                        when (result) {
                            TcpProbeResult.OPEN -> open.add(port)
                            TcpProbeResult.NO_CONNECTION -> noConnection.incrementAndGet()
                            TcpProbeResult.TIMEOUT -> timedOut.incrementAndGet()
                            TcpProbeResult.ERROR -> errors.incrementAndGet()
                        }
                        val count = tested.incrementAndGet()
                        if (count % 32 == 0 || count == total) onProgress(TcpScanProgress(count, total, open.toList(),
                            noConnection.get(), timedOut.get(), errors.get()))
                    }
                }
            }
        }
        return TcpScanProgress(tested.get(), total, open.toList(), noConnection.get(), timedOut.get(), errors.get())
    }
}
