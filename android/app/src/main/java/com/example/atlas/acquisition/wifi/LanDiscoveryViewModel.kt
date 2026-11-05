package com.example.atlas.acquisition.wifi

import android.content.Context
import android.net.Network
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LanDiscoveryState(
    val running: Boolean = false,
    val target: LanTarget? = null,
    val progress: LanProgress? = null,
    val services: List<LanServiceObservation> = emptyList(),
    val serviceNotice: String? = null,
    val status: String = "Idle",
    val error: String? = null
)

fun interface LanServiceBrowse {
    suspend fun browse(network: Network, target: LanTarget,
                       onObservation: (LanServiceObservation) -> Unit): Int
}

class LanDiscoveryViewModel(private val connection: suspend () -> LanConnection,
                            private val serviceBrowse: LanServiceBrowse,
                            private val scanLimitMillis: Long = 60_000) : ViewModel() {
    init { require(scanLimitMillis > 0) }
    private val mutable = MutableStateFlow(LanDiscoveryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var runId = 0L

    fun start() {
        if (job?.isActive == true) return
        val id = ++runId
        mutable.value = LanDiscoveryState(running = true, status = "Finding Wi-Fi IPv4 range")
        job = viewModelScope.launch {
            try {
                val (target, probe, network) = connection()
                if (runId != id) return@launch
                mutable.value = LanDiscoveryState(running = true, target = target,
                    progress = LanProgress(0, target.hosts.size, emptyList()), status = "Checking addresses")
                withTimeout(scanLimitMillis) {
                    val browsing = async {
                        try {
                            val failures = serviceBrowse.browse(network, target) { row ->
                                if (runId == id) mutable.update { current ->
                                    if (current.services.size >= 64 || row in current.services) current
                                    else current.copy(services = current.services + row)
                                }
                            }
                            when {
                                Build.VERSION.SDK_INT < 33 -> "Advertised names require Android 13 or later for Wi-Fi-bound discovery."
                                failures > 0 -> "mDNS browse had $failures query or resolution errors; names may be incomplete."
                                else -> "mDNS browse finished across ${AndroidLanServiceBrowser.serviceTypes.size} service types."
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { "Service discovery failed: ${failure.message ?: failure.javaClass.simpleName}" }
                    }
                    val result = LanDiscovery(probe).scan(target) { progress ->
                        if (runId == id) mutable.update { current ->
                            if (progress.checked >= (current.progress?.checked ?: 0)) current.copy(progress = progress) else current
                        }
                    }
                    val notice = browsing.await()
                    if (runId == id) mutable.update {
                        val hasWarnings = result.probeErrors > 0 || !notice.startsWith("mDNS browse finished")
                        it.copy(running = false, progress = result,
                            status = if (hasWarnings) "Completed with warnings" else "Completed",
                            serviceNotice = "${it.services.size} in-subnet service observations. $notice")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (runId == id) mutable.update { it.copy(running = false, status = "Time limit reached; partial results",
                    serviceNotice = "Discovery stopped at its time limit; results may be incomplete.") }
            } catch (_: CancellationException) {
                if (runId == id) mutable.update { it.copy(running = false, status = "Stopped; partial results") }
            } catch (failure: Exception) {
                if (runId == id) mutable.update { it.copy(running = false, status = "Discovery failed",
                    error = failure.message ?: failure.javaClass.simpleName) }
            }
        }
    }

    fun stop() {
        if (job?.isActive != true) return
        runId++
        job?.cancel()
        mutable.update { it.copy(running = false, status = "Stopped; partial results") }
    }

    override fun onCleared() { stop() }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val provider = AndroidLanDiscovery(context)
        private val browser = AndroidLanServiceBrowser(context)
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LanDiscoveryViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return LanDiscoveryViewModel(provider::connection, browser::browse) as T
        }
    }
}
