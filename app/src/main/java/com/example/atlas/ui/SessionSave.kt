package com.example.atlas.ui

import androidx.compose.runtime.Immutable
import com.example.atlas.acquisition.bluetooth.BluetoothUiState
import com.example.atlas.acquisition.bluetooth.BluetoothViewModel
import com.example.atlas.acquisition.cellular.CellularUiState
import com.example.atlas.acquisition.cellular.CellularViewModel
import com.example.atlas.acquisition.nfc.NfcUiState
import com.example.atlas.acquisition.nfc.NfcViewModel
import com.example.atlas.acquisition.wifi.WifiUiState
import com.example.atlas.acquisition.wifi.WifiViewModel
import com.example.atlas.telemetry.TelemetryNormalizer
import com.example.atlas.telemetry.TelemetryViewModel
import com.example.atlas.telemetry.UnsavedData

/** B3: unsaved-data cue for one radio screen's control card. */
@Immutable
data class UnsavedCue(val count: Int, val busy: Boolean, val available: Boolean)

object SessionSave {
    /** Observation timestamps per radio for the unsaved-data cue; buffers are de-duplicated the same way saving is. */
    fun timestamps(ble: BluetoothUiState, wifi: WifiUiState, cell: CellularUiState, nfc: NfcUiState): Map<String, List<Long>> = mapOf(
        "BLUETOOTH" to ble.history.map { it.observedAtMillis },
        "WIFI" to (wifi.history + wifi.rows).distinctBy { it.eventId }.map { it.observedAtMillis } +
            wifi.connections.map { it.observedAtMillis },
        "CELLULAR" to (cell.history + listOfNotNull(cell.cells)).distinctBy { it.eventId }.map { it.receivedAtMillis } +
            (cell.signalHistory + listOfNotNull(cell.signal)).distinct().map { it.receivedAtMillis },
        "NFC" to nfc.tags.map { it.observedAtMillis }
    )

    fun unsavedCounts(ble: BluetoothUiState, wifi: WifiUiState, cell: CellularUiState, nfc: NfcUiState, savedThrough: Map<String, Long>) =
        UnsavedData.counts(timestamps(ble, wifi, cell, nfc), savedThrough)
}

/**
 * The one save routine behind History's "Save session measurements" and each radio screen's "Save to History" (B3).
 * Reads every buffer at click time, captures immutable snapshots, then normalizes off the UI thread (unchanged logic,
 * moved out of AtlasApp).
 */
fun saveSessionMeasurements(
    telemetry: TelemetryViewModel,
    bluetooth: BluetoothViewModel, wifiSurvey: WifiViewModel, cellular: CellularViewModel, nfc: NfcViewModel,
    blePermissionsMissing: List<String>, wifiPermissionsMissing: List<String>, cellularPermissionsMissing: List<String>
) {
    val ble = bluetooth.state.value
    val wifi = wifiSurvey.state.value
    val cell = cellular.state.value
    val nfcState = nfc.state.value
    val bleSnapshot = ble.history
    val wifiSnapshot = wifi.history + wifi.rows
    val connectionSnapshot = wifi.connections
    val cellSnapshot = cell.history + listOfNotNull(cell.cells)
    val signalSnapshot = cell.signalHistory + listOfNotNull(cell.signal)
    val nfcSnapshot = nfcState.tags
    val errors = listOf(Triple("BLUETOOTH", ble.error, blePermissionsMissing), Triple("WIFI", wifi.error, wifiPermissionsMissing), Triple("CELLULAR", cell.error, cellularPermissionsMissing))
    val savedAt = System.currentTimeMillis()
    val markers = UnsavedData.markers(SessionSave.timestamps(ble, wifi, cell, nfcState))
    telemetry.save(markers) {
        buildList {
            addAll(bleSnapshot.map { TelemetryNormalizer.ble(it, telemetry.bootId) })
            addAll(wifiSnapshot.flatMap { TelemetryNormalizer.wifi(it, telemetry.bootId) })
            addAll(connectionSnapshot.flatMap { TelemetryNormalizer.connection(it, telemetry.bootId) })
            addAll(cellSnapshot.flatMap { TelemetryNormalizer.cells(it, telemetry.bootId) })
            addAll(signalSnapshot.flatMap { TelemetryNormalizer.signal(it, telemetry.bootId) })
            addAll(nfcSnapshot.flatMap { TelemetryNormalizer.nfc(it, telemetry.bootId) })
            errors.forEach { (radio, error, missing) ->
                if (error != null) add(TelemetryNormalizer.diagnostic(radio, if (missing.isEmpty()) "FAILED" else "PERMISSION_DENIED",
                    if (missing.isEmpty()) "GRANTED_AT_SAVE" else "DENIED_AT_SAVE", savedAt, "Atlas collection state", error))
            }
        }
    }
}
