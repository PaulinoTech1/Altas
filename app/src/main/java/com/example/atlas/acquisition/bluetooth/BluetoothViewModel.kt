package com.example.atlas.acquisition.bluetooth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class BluetoothUiState(
    val scanning: Boolean = false,
    val config: BleScanConfig = BleScanConfig(),
    val devices: List<BleDeviceHistory> = emptyList(),
    val history: List<BleObservation> = emptyList(),
    val receivedCount: Long = 0,
    val status: String = "Idle — no BLE scan started",
    val error: String? = null,
    val selectedAddress: String? = null
)

class BluetoothViewModel(private val scanner: BleScanner, val gatt: GattClient) : ViewModel() {
    private val mutable = MutableStateFlow(BluetoothUiState())
    val state = mutable.asStateFlow()
    private val store = BleHistoryStore()
    private var scanJob: Job? = null

    fun configure(config: BleScanConfig) {
        if (!mutable.value.scanning) mutable.value = mutable.value.copy(config = config)
    }

    fun start() {
        if (scanJob?.isCompleted == false) return
        val config = mutable.value.config
        mutable.value = mutable.value.copy(scanning = true, status = "Scanning for ${config.seconds} seconds", error = null)
        scanJob = viewModelScope.launch {
            var lastPublishNanos = 0L
            try {
                withTimeout(config.seconds * 1000L) {
                    scanner.scan(config).collect { observation ->
                        store.add(observation)
                        if (observation.receivedElapsedNanos - lastPublishNanos >= 200_000_000) {
                            publish()
                            lastPublishNanos = observation.receivedElapsedNanos
                        }
                    }
                }
                mutable.value = mutable.value.copy(status = "Scan finished")
            } catch (_: TimeoutCancellationException) {
                mutable.value = mutable.value.copy(status = "Scan time limit reached")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.value = mutable.value.copy(status = "Scan failed", error = when (failure) {
                    is SecurityException -> "Android denied BLE access. Review Nearby devices and location permissions."
                    is IllegalStateException -> failure.message ?: "BLE service unavailable."
                    else -> "BLE operation failed (${failure.javaClass.simpleName}). Stop and review hardware/permissions."
                })
            } finally {
                mutable.value = mutable.value.copy(scanning = false)
                publish()
            }
        }
    }

    fun stop(reason: String = "Stopped by user") {
        scanJob?.cancel()
        mutable.value = mutable.value.copy(scanning = false, status = reason)
    }

    fun clearHistory() { if (!mutable.value.scanning) { store.clear(); publish() } }
    fun select(address: String?) { mutable.value = mutable.value.copy(selectedAddress = address) }
    fun connect(address: String) {
        if (mutable.value.devices.none { it.latest.address == address && it.latest.connectable }) return
        stop("Stopped for authorized GATT connection")
        gatt.connect(address)
    }
    fun stopAll() { stop("Stopped — Bluetooth screen inactive"); gatt.disconnect() }

    private fun publish() {
        mutable.value = mutable.value.copy(devices = store.devices(), history = store.observations(), receivedCount = store.receivedCount)
    }

    override fun onCleared() { stopAll() }

    class Factory(private val scanner: BleScanner, private val gatt: GattClient) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BluetoothViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return BluetoothViewModel(scanner, gatt) as T
        }
    }
}
