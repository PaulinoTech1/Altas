package com.example.atlas.ui.viz

import android.os.SystemClock
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.acquisition.bluetooth.BleSignalAnalysis
import com.example.atlas.ui.DiagnosticNotice
import kotlinx.coroutines.delay

/** Foreground, user-started RSSI search. It does not locate a peer on a map or command it to ring. */
@Composable
fun BleSignalLocator(
    history: List<BleObservation>, selectedAddress: String?, scanning: Boolean, startedAtMillis: Long?,
    canScan: Boolean, scanError: String?, onStart: () -> Unit, onStop: () -> Unit
) {
    var guided by remember(selectedAddress) { mutableStateOf(false) }
    val sweepHistory = remember(history, scanning, startedAtMillis) {
        if (scanning && startedAtMillis != null) history.filter { it.observedAtMillis >= startedAtMillis } else history
    }
    val evidence = remember(sweepHistory, selectedAddress) { BleSignalAnalysis.summarize(sweepHistory, selectedAddress) }
    val nowElapsedNanos by produceState(SystemClock.elapsedRealtimeNanos(), scanning, selectedAddress) {
        while (scanning) {
            delay(1_000)
            value = SystemClock.elapsedRealtimeNanos()
        }
    }
    val reading = BleLocatorMath.evaluate(history, selectedAddress, scanning, startedAtMillis, nowElapsedNanos)
    val colors = MaterialTheme.colorScheme
    ChartCard("BLE signal locator") {
        scanError?.let { DiagnosticNotice("BLE", it) }
        if (selectedAddress == null) {
            Text("Select a BLE point in the 3D view or choose Locate signal on a device row.")
        } else {
            val name = history.firstOrNull { it.address == selectedAddress }?.advertisement?.name
            Text("Target: ${name?.takeIf { it.isNotBlank() } ?: "Unnamed BLE address"} · $selectedAddress",
                style = MaterialTheme.typography.titleSmall)
            val stateLine = when (reading.status) {
                BleLocatorMath.Status.SELECT_TARGET -> "Select a target."
                BleLocatorMath.Status.WAITING -> "Searching: no advertisement from this address in the current scan."
                BleLocatorMath.Status.LIVE -> "Live signal · last heard ${reading.ageMillis!! / 1_000} s ago"
                BleLocatorMath.Status.STALE -> "Signal stale · last heard ${reading.ageMillis!! / 1_000} s ago"
                BleLocatorMath.Status.ENDED -> "Scan ended · last reading is saved in memory, not live."
                BleLocatorMath.Status.NO_SIGNAL -> "No retained signal for this address."
            }
            Text(stateLine, style = MaterialTheme.typography.bodyMedium,
                color = if (reading.status == BleLocatorMath.Status.LIVE) colors.primary else colors.onSurfaceVariant)
            if (reading.latestDbm != null) {
                Text("Latest ${reading.latestDbm} dBm · strongest retained ${reading.bestDbm} dBm · " +
                    "${reading.sampleCount} ${if (reading.sampleCount == 1) "reading" else "readings"} (latest 256 max)")
                if (reading.status == BleLocatorMath.Status.LIVE) {
                    LinearProgressIndicator(
                        progress = { ((reading.latestDbm + 100f) / 70f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                }
            }
            val trend = reading.trendDb
            Text(if (trend == null) "RSSI trend unavailable until 6 fresh readings."
                else when {
                    trend >= 6 -> "RSSI rising by $trend dB across recent readings."
                    trend <= -6 -> "RSSI falling by ${-trend} dB across recent readings."
                    else -> "RSSI change is small or unclear ($trend dB)."
                }, style = MaterialTheme.typography.bodySmall)
        }
        if (scanning) {
            OutlinedButton(onClick = onStop) { Text("Stop BLE search") }
        } else {
            OutlinedButton(onClick = { guided = false; onStart() }, enabled = selectedAddress != null && canScan) {
                Text("Start 20s focused search")
            }
            OutlinedButton(onClick = { guided = true; onStart() }, enabled = selectedAddress != null && canScan) {
                Text("Start guided 20s sweep")
            }
            if (!canScan) Text("Grant BLE survey permissions to start a search.", style = MaterialTheme.typography.bodySmall)
        }
        if (guided && selectedAddress != null) {
            Text(if (scanning) "Sweep in progress: stay in one place and slowly turn the phone through a full circle. " +
                "Keep the same grip. Atlas does not measure your position."
                else "Sweep review: ${evidence.pairedSamples} paired readings across " +
                    "${evidence.comparableSectors.size} repeated phone-orientation sectors. " +
                    if (evidence.repeatableCoverage) "Repeat from the same spot and compare patterns; no bearing is inferred."
                    else "Inconclusive coverage; repeat only after the scan cooldown.",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("Walk slowly and compare several fresh readings. A stronger RSSI can suggest a better search area, but reflections, body position, transmit power and rotating addresses can mislead it. No direction, distance, map location or remote ring is available through this BLE scan.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
