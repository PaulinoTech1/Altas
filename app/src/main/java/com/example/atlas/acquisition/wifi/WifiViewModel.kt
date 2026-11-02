package com.example.atlas.acquisition.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.atlas.model.SessionClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class WifiUiState(
    val collecting: Boolean = false,
    val status: String = "Idle — no Wi-Fi survey started",
    val rows: List<WifiObservation> = emptyList(),
    val totalRows: Int = 0,
    val history: List<WifiObservation> = emptyList(),
    val connections: List<WifiConnection> = emptyList(),
    val request: WifiScanRequest? = null,
    val osScanThrottlingEnabled: Boolean? = null,
    val scanBroadcasts: Int = 0,
    val updatedBroadcasts: Int = 0,
    val selectedBssid: String? = null,
    val error: String? = null,
    /** [SessionClock] time the running survey started, or null when idle; drives the UI countdown only (B2). */
    val sessionStartedAtMillis: Long? = null,
    val sessionLimitMillis: Long = WifiViewModel.SESSION_LIMIT_MILLIS
)

class WifiViewModel(private val repository: WifiSurveyRepository) : ViewModel() {
    private val mutable = MutableStateFlow(WifiUiState())
    val state = mutable.asStateFlow()
    private val store = WifiHistoryStore()
    private var session: Job? = null
    private var requestJob: Job? = null

    fun start() {
        if (session?.isCompleted == false) return
        mutable.value = mutable.value.copy(collecting = true, status = "Survey active (60-second maximum)", connections = emptyList(), error = null,
            sessionStartedAtMillis = SessionClock.nowMillis())
        session = viewModelScope.launch {
            try {
                withTimeout(SESSION_LIMIT_MILLIS) {
                    repository.survey().collect { event ->
                        when (event) {
                            is WifiEvent.Policy -> mutable.value = mutable.value.copy(osScanThrottlingEnabled = event.osScanThrottlingEnabled)
                            is WifiEvent.Results -> {
                                store.add(event.rows)
                                mutable.value = mutable.value.copy(rows = event.rows, totalRows = event.totalRows, history = store.observations(),
                                    scanBroadcasts = mutable.value.scanBroadcasts + if (event.delivery != WifiDelivery.CACHE_READ) 1 else 0,
                                    updatedBroadcasts = mutable.value.updatedBroadcasts + if (event.delivery == WifiDelivery.UPDATED_BROADCAST) 1 else 0,
                                    error = if (event.delivery == WifiDelivery.NOT_UPDATED_BROADCAST) "Android reported no updated results. Displayed rows may be cached." else null)
                            }
                            is WifiEvent.Connection -> mutable.value = mutable.value.copy(
                                connections = (mutable.value.connections.filterNot { it.key == event.value.key } + event.value).takeLast(8))
                            is WifiEvent.ConnectionLost -> mutable.value = mutable.value.copy(connections = mutable.value.connections.filterNot { it.key == event.key })
                            is WifiEvent.Request -> mutable.value = mutable.value.copy(request = event.value)
                        }
                    }
                }
                mutable.value = mutable.value.copy(status = "Survey finished")
            } catch (_: TimeoutCancellationException) {
                mutable.value = mutable.value.copy(status = "Survey time limit reached")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = mutable.value.copy(status = "Survey failed", error = errorLabel(failure)) }
            finally {
                requestJob?.cancel()
                mutable.value = mutable.value.copy(collecting = false, sessionStartedAtMillis = null)
            }
        }
    }

    fun requestScan() {
        if (!mutable.value.collecting || requestJob?.isActive == true) return
        requestJob = viewModelScope.launch {
            try { mutable.value = mutable.value.copy(request = repository.requestScan(), error = null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                stop("Stopped after scan request failure")
                mutable.value = mutable.value.copy(error = errorLabel(failure))
            }
        }
    }

    fun stop(reason: String = "Stopped by user — retained snapshots are not live") {
        requestJob?.cancel(); session?.cancel()
        mutable.value = mutable.value.copy(collecting = false, status = reason, sessionStartedAtMillis = null)
    }
    fun select(bssid: String?) { mutable.value = mutable.value.copy(selectedBssid = bssid) }
    fun clear() { if (!mutable.value.collecting) { store.clear(); mutable.value = WifiUiState() } }
    override fun onCleared() { stop() }

    private fun errorLabel(failure: Exception) = when (failure) {
        is SecurityException -> "Android denied Wi-Fi access. Review precise location permission and enabled location services."
        is IllegalStateException -> failure.message ?: "Wi-Fi service unavailable."
        else -> "Wi-Fi survey failed (${failure.javaClass.simpleName})."
    }
    companion object { const val SESSION_LIMIT_MILLIS = 60_000L }
    class Factory(private val repository: WifiSurveyRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WifiViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return WifiViewModel(repository) as T
        }
    }
}
