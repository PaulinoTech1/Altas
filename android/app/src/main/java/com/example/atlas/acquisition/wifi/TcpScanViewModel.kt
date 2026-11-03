package com.example.atlas.acquisition.wifi

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TcpScanUiState(
    val running: Boolean = false,
    val host: String? = null,
    val progress: TcpScanProgress? = null,
    val status: String = "Idle",
    val error: String? = null
)

class TcpScanViewModel(private val probeProvider: suspend () -> TcpProbe) : ViewModel() {
    private val mutable = MutableStateFlow(TcpScanUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var runId = 0L

    fun start(request: TcpScanRequest) {
        if (job?.isActive == true) return
        TcpScanPolicy.validate(request)?.let { mutable.value = TcpScanUiState(error = it); return }
        val id = ++runId
        mutable.value = TcpScanUiState(running = true, host = request.host.trim(),
            progress = TcpScanProgress(0, request.lastPort - request.firstPort + 1, emptyList(), 0, 0, 0), status = "Scanning")
        job = viewModelScope.launch {
            try {
                val scanner = TcpPortScanner(probeProvider())
                val result = scanner.scan(request) { update ->
                    if (runId == id) mutable.update { current ->
                        if (update.tested >= (current.progress?.tested ?: 0)) current.copy(progress = update) else current
                    }
                }
                if (runId == id) mutable.update { it.copy(running = false, progress = result, status = "Completed") }
            } catch (_: CancellationException) {
                if (runId == id) mutable.update { it.copy(running = false, status = "Stopped; partial results") }
            } catch (failure: Exception) {
                if (runId == id) mutable.update { it.copy(running = false, status = "Scan failed",
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
        private val provider = AndroidWifiTcpProbe(context)
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TcpScanViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return TcpScanViewModel(provider::wifiProbe) as T
        }
    }
}
