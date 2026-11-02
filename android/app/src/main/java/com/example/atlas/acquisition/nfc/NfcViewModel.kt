package com.example.atlas.acquisition.nfc

import android.app.Activity
import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.atlas.acquisition.bluetooth.hex
import com.example.atlas.model.SessionClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference

class NfcViewModel(private val reader: AndroidNfcReader) : ViewModel() {
    private val mutable = MutableStateFlow(NfcUiState())
    val state = mutable.asStateFlow()
    private val operation = Mutex()
    private var activity: WeakReference<Activity>? = null
    private var lastTag: Tag? = null
    private var tagJob: Job? = null
    private var apduJob: Job? = null
    private var timeoutJob: Job? = null

    fun start(owner: Activity, readNdef: Boolean = false) {
        if (mutable.value.reading) return
        mutable.update { it.copy(reading = true, status = "Starting NFC reader…", error = null) }
        try {
            reader.start(owner, readNdef) { tag ->
                viewModelScope.launch {
                    if (!mutable.value.reading) return@launch
                    tagJob?.cancel()
                    tagJob = launch {
                        try {
                            operation.withLock {
                                val report = reader.inspect(tag, readNdef)
                                if (mutable.value.reading) {
                                    lastTag = tag
                                    mutable.update { it.copy(tags = (listOf(report) + it.tags).take(32), status = "Tag detected", error = null) }
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            mutable.update { it.copy(error = "Tag inspection failed (${failure.javaClass.simpleName}). Move the tag away and retry.") }
                        }
                    }
                }
            }
            activity = WeakReference(owner)
            mutable.update { it.copy(status = "${if (readNdef) "NDEF" else "Metadata-only"} reader active for up to 60 seconds. Hold an authorized tag near the phone.",
                sessionStartedAtMillis = SessionClock.nowMillis()) }
            timeoutJob = viewModelScope.launch { delay(SESSION_LIMIT_MILLIS); stop("Reader time limit reached") }
        } catch (failure: Exception) {
            mutable.update { it.copy(reading = false, status = "Reader unavailable", error = failure.message ?: failure.javaClass.simpleName) }
        }
    }

    fun sendApdu(hex: String) {
        if (!mutable.value.reading || mutable.value.apduBusy) return
        val tag = lastTag ?: run { mutable.update { it.copy(error = "Scan an ISO-DEP tag first.") }; return }
        val command = try { ApduInput.parse(hex) }
            catch (failure: IllegalArgumentException) { mutable.update { it.copy(error = failure.message) }; return }
        if (mutable.value.tags.firstOrNull()?.isoDep != true) {
            mutable.update { it.copy(error = "The last detected tag does not support ISO-DEP.") }
            return
        }
        mutable.update { it.copy(apduBusy = true, error = null) }
        apduJob = viewModelScope.launch {
            try {
                operation.withLock {
                    val response = reader.exchange(tag, command)
                    val hexResponse = response.hex()
                    val statusWord = if (response.size >= 2) response.takeLast(2).toByteArray().hex() else null
                    if (mutable.value.reading) mutable.update {
                        it.copy(exchanges = (listOf(ApduExchange(System.currentTimeMillis(), command.hex(), hexResponse, statusWord)) + it.exchanges).take(20),
                            status = "ISO-DEP response received")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = "ISO-DEP exchange failed (${failure.javaClass.simpleName}). The tag may have moved, rejected the command, or returned an oversized response.") } }
            finally { mutable.update { it.copy(apduBusy = false) } }
        }
    }

    fun stop(reason: String = "Stopped by user") {
        timeoutJob?.cancel(); timeoutJob = null
        tagJob?.cancel(); tagJob = null
        apduJob?.cancel(); apduJob = null
        reader.closeIo()
        activity?.get()?.let { owner ->
            try { reader.stop(owner) } catch (_: SecurityException) { /* Permission or service state changed. */ }
        }
        activity = null
        lastTag = null
        mutable.update { it.copy(reading = false, apduBusy = false, status = reason, sessionStartedAtMillis = null) }
    }

    fun clear() { if (!mutable.value.reading) mutable.update { it.copy(tags = emptyList(), exchanges = emptyList()) } }

    override fun onCleared() { stop("Reader closed") }

    companion object { const val SESSION_LIMIT_MILLIS = 60_000L }
    class Factory(private val reader: AndroidNfcReader) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NfcViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return NfcViewModel(reader) as T
        }
    }
}
