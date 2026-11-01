package com.example.atlas.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.atlas.data.CapabilityRepository
import com.example.atlas.model.RadioCapability
import com.example.atlas.model.RadioType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardState(
    val radios: List<RadioCapability> = RadioType.entries.map { RadioCapability(it) },
    val refreshing: Boolean = false,
    val error: String? = null
)

class DashboardViewModel(private val repository: CapabilityRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(DashboardState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(refreshing = true, error = null)
            try {
                mutableState.value = DashboardState(radios = repository.snapshot())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    refreshing = false, error = "Capability check failed. Refresh to retry; previous values may be stale."
                )
            }
        }
    }

    fun pause() {
        refreshJob?.cancel()
        mutableState.value = mutableState.value.copy(refreshing = false)
    }

    class Factory(private val repository: CapabilityRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DashboardViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(repository) as T
        }
    }
}
