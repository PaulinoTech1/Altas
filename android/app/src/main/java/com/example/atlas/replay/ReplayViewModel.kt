package com.example.atlas.replay

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.atlas.telemetry.TelemetryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class ReplayState(
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val timeline: ReplayTimeline? = null,
    val index: Int = 0,
    val error: String? = null
) {
    val frame: ReplayFrame? get() = timeline?.frames?.getOrNull(index)
}

/**
 * V5 "Demo replay": loops the user's OWN saved records, one saved frame per second. Kept apart from every live
 * ViewModel: it only reads [MeasurementDao.replayRows], never writes, never starts a collector, and nothing here is
 * saved or exported. State is plain memory (no SavedStateHandle, no preferences), so it is off after process death.
 */
class ReplayViewModel(private val load: suspend () -> ReplayTimeline) : ViewModel() {
    private val mutable = MutableStateFlow(ReplayState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun setEnabled(enabled: Boolean) {
        job?.cancel()
        job = null
        if (!enabled) { mutable.value = ReplayState(); return }
        mutable.value = ReplayState(enabled = true, loading = true)
        job = viewModelScope.launch {
            val timeline = try { load() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    mutable.update { it.copy(loading = false, error = "Could not read saved telemetry (${failure.javaClass.simpleName}).") }
                    return@launch
                }
            mutable.update { it.copy(loading = false, timeline = timeline, index = 0) }
            if (timeline.frames.size < 2) return@launch
            while (isActive) {
                delay(FRAME_MILLIS)
                mutable.update { it.copy(index = (it.index + 1) % timeline.frames.size) }
            }
        }
    }

    companion object {
        const val FRAME_MILLIS = 1_000L
    }

    class Factory(private val repository: TelemetryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReplayViewModel {
            withContext(Dispatchers.Default) { ReplayFrames.build(repository.dao.replayRows(ReplayFrames.MAX_ROWS)) }
        } as T
    }
}
