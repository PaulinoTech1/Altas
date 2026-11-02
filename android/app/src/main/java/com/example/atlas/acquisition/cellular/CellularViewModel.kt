package com.example.atlas.acquisition.cellular

import androidx.compose.runtime.Immutable
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

@Immutable // B8: replaced wholesale on every update; never mutated in place.
data class CellularUiState(
    val collecting: Boolean = false, val status: String = "Idle — no cellular collection started",
    val subscriptions: List<CellularSubscription> = emptyList(), val selectedId: Int? = null,
    val scopeMessage: String = "Subscription access has not been requested.",
    val cells: CellBatch? = null, val signal: SignalReport? = null, val service: ServiceReport? = null,
    val display: DisplayReport? = null, val request: CellUpdateResult? = null,
    val history: List<CellBatch> = emptyList(), val signalHistory: List<SignalReport> = emptyList(),
    val eventCount: Long = 0, val error: String? = null,
    /** [SessionClock] time the running collection started, or null when idle; drives the UI countdown only (B2). */
    val sessionStartedAtMillis: Long? = null,
    val sessionLimitMillis: Long = CellularViewModel.SESSION_LIMIT_MILLIS
)

class CellularViewModel(private val repository: CellularRepository) : ViewModel() {
    private val mutable = MutableStateFlow(CellularUiState())
    val state = mutable.asStateFlow()
    private var session: Job? = null
    private var update: Job? = null

    fun start() {
        if (session?.isCompleted == false) return
        val selected = mutable.value.selectedId
        mutable.value = mutable.value.copy(collecting = true, status = "Cellular collection active (60-second maximum)",
            cells = null, signal = null, service = null, display = null, request = null, error = null,
            sessionStartedAtMillis = SessionClock.nowMillis())
        session = viewModelScope.launch {
            try {
                withTimeout(SESSION_LIMIT_MILLIS) {
                    repository.observe(selected).collect { event ->
                        mutable.value = mutable.value.copy(eventCount = mutable.value.eventCount + 1)
                        when (event) {
                            is CellularEvent.Session -> mutable.value = mutable.value.copy(subscriptions = event.subscriptions, selectedId = event.selectedId, scopeMessage = event.message)
                            is CellularEvent.Cells -> accept(event.batch)
                            is CellularEvent.Signal -> mutable.value = mutable.value.copy(signal = event.report,
                                signalHistory = (listOf(event.report) + mutable.value.signalHistory).take(128))
                            is CellularEvent.Service -> mutable.value = mutable.value.copy(service = event.report)
                            is CellularEvent.Display -> mutable.value = mutable.value.copy(display = event.report)
                            is CellularEvent.Update -> {
                                mutable.value = mutable.value.copy(request = event.result)
                                event.result.batch?.let(::accept)
                            }
                        }
                    }
                }
                mutable.value = mutable.value.copy(status = "Collection finished")
            } catch (_: TimeoutCancellationException) { mutable.value = mutable.value.copy(status = "Cellular session time limit reached") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = mutable.value.copy(status = "Cellular collection failed", error = errorLabel(failure)) }
            finally { update?.cancel(); finishPendingRequest(); mutable.value = mutable.value.copy(collecting = false, sessionStartedAtMillis = null) }
        }
    }

    private fun accept(batch: CellBatch) {
        val history = mutable.value.history
        val duplicate = history.any { it.subscriptionContext == batch.subscriptionContext && it.rows == batch.rows }
        mutable.value = mutable.value.copy(cells = batch,
            history = if (batch.rows.isEmpty() || duplicate) history else (listOf(batch) + history).take(64))
    }

    fun requestUpdate() {
        val selected = mutable.value.selectedId ?: return
        if (!mutable.value.collecting || update?.isActive == true || mutable.value.request?.status == CellRequestStatus.PENDING) return
        update = viewModelScope.launch {
            try {
                mutable.value = mutable.value.copy(request = CellUpdateResult(CellRequestStatus.PENDING, "Cell update pending; no duplicate request submitted."))
                val result = repository.requestUpdate(selected)
                mutable.value = mutable.value.copy(request = result)
                result.batch?.let(::accept)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { stop(); mutable.value = mutable.value.copy(error = errorLabel(failure)) }
        }
    }
    fun stop(reason: String = "Stopped — retained reports are snapshots") {
        update?.cancel(); session?.cancel()
        finishPendingRequest()
        mutable.value = mutable.value.copy(collecting = false, status = reason, sessionStartedAtMillis = null)
    }
    private fun finishPendingRequest() {
        if (mutable.value.request?.status == CellRequestStatus.PENDING) mutable.value = mutable.value.copy(
            request = CellUpdateResult(CellRequestStatus.CANCELLED, "Atlas stopped waiting for the update. Cancellation of an already-submitted modem request is not claimed."))
    }
    fun select(id: Int) {
        if (mutable.value.subscriptions.none { it.id == id }) return
        stop("Subscription selected — start collection explicitly")
        mutable.value = mutable.value.copy(selectedId = id, cells = null, signal = null, service = null, display = null, request = null)
    }
    fun clear() { if (!mutable.value.collecting) mutable.value = CellularUiState() }
    override fun onCleared() { stop() }
    private fun errorLabel(failure: Exception) = when (failure) {
        is SecurityException -> "Android denied cellular access. Review Phone status and precise location permissions."
        is UnsupportedOperationException -> "The selected public telephony API is unsupported by this device."
        is IllegalStateException -> failure.message ?: "Telephony service unavailable."
        else -> "Cellular operation failed (${failure.javaClass.simpleName})."
    }
    companion object { const val SESSION_LIMIT_MILLIS = 60_000L }
    class Factory(private val repository: CellularRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CellularViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return CellularViewModel(repository) as T
        }
    }
}
