package com.example.atlas.telemetry

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.atlas.ui.UiText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TelemetryUiState(
    val preferences: StoragePreferences = StoragePreferences(), val loaded: Boolean = false,
    val count: Int = 0, val busy: Boolean = false, val message: String? = null, val error: String? = null
)

class TelemetryViewModel(private val repository: TelemetryRepository) : ViewModel() {
    private val mutable = MutableStateFlow(TelemetryUiState())
    val state = mutable.asStateFlow()
    val bootId get() = repository.bootId
    private val filter = MutableStateFlow(Triple("", "", 0))
    val historyPage = filter.map { it.third / 200 + 1 }.stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    @OptIn(ExperimentalCoroutinesApi::class)
    val history = filter.flatMapLatest { (radio, query, offset) -> repository.dao.history(radio, query, offset) }
        .catch { failure -> mutable.update { it.copy(error = "Could not read saved telemetry (${failure.javaClass.simpleName}).") }; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            try {
                repository.maintain()
                launch { repository.dao.count().collect { count -> mutable.update { it.copy(count = count) } } }
                repository.preferences.values.collect { settings -> mutable.update { it.copy(preferences = settings, loaded = true) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail("Storage initialization failed", failure) }
        }
    }

    fun filter(radio: String, query: String) { filter.value = Triple(radio, query.take(100), 0) }
    fun page(delta: Int) { filter.update { it.copy(third = (it.third + delta * 200).coerceIn(0, 49800)) } }
    fun save(buildRows: () -> List<Measurement>) = operation("Save failed") {
        val rows = withContext(Dispatchers.Default) { buildRows() }
        val count = repository.save(rows)
        "Save finished: $count new records processed. Retention limits applied; repeated observations are ignored."
    }
    fun configure(value: StoragePreferences) = operation("Storage preferences update failed") {
        "Storage preferences saved. ${UiText.retentionResult(repository.configure(value))}."
    }
    fun export(uri: Uri?, redact: Boolean) {
        if (uri == null) { mutable.update { it.copy(message = "Export cancelled; no document written.") }; return }
        operation("Export failed before writing; the chosen document was not written") { "Exported ${repository.export(uri, redact)} records (${if (redact) "identifiers redacted" else "includes identifiers"})." }
    }
    fun exportUnavailable() { mutable.update { it.copy(error = "No document picker is available. Export was not started.") } }
    fun refresh() = operation("Retention check failed") { "${UiText.retentionResult(repository.maintain())}." }
    private fun operation(failurePrefix: String, action: suspend () -> String) {
        if (mutable.value.busy || !mutable.value.loaded) return
        mutable.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try { val message = action(); mutable.update { it.copy(message = message) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failurePrefix, failure) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun fail(prefix: String, failure: Exception) {
        val incomplete = failure as? TelemetryExport.DestinationException
        mutable.update { it.copy(error = if (incomplete != null) "${incomplete.message} (${(incomplete.cause ?: incomplete).javaClass.simpleName})."
            else "$prefix (${failure.javaClass.simpleName}).") }
    }
    class Factory(private val repository: TelemetryRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TelemetryViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return TelemetryViewModel(repository) as T
        }
    }
}
