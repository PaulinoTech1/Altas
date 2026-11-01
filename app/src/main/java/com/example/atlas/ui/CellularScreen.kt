package com.example.atlas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.atlas.acquisition.cellular.*
import com.example.atlas.model.RadioCapability
import java.text.DateFormat
import java.util.Date

fun LazyListScope.cellularContent(
    radio: RadioCapability, viewModel: CellularViewModel, state: CellularUiState,
    missingPermissions: List<String>, nowElapsedMillis: Long,
    requestPermissions: () -> Unit, openSettings: () -> Unit
) {
    item {
        Text("Hardware: ${radio.availability.label}", style = MaterialTheme.typography.titleMedium)
        Text("Access: ${if (missingPermissions.isEmpty()) "Granted" else "Phone status and precise location required"}")
        Text("Cell measurements can reveal location. Phone status is used for subscription and radio callbacks. No phone numbers, IMSI, IMEI or ICCID are requested.")
        OutlinedButton(onClick = requestPermissions) { Text("Review cellular permissions") }
        TextButton(onClick = openSettings) { Text("App permission settings") }
        if (missingPermissions.isNotEmpty()) PermissionGuidance()
        if (state.collecting) {
            Button(onClick = { viewModel.stop() }) { Text("Stop cellular collection") }
            OutlinedButton(onClick = viewModel::requestUpdate, enabled = state.selectedId != null && state.request?.status != CellRequestStatus.PENDING) { Text("Request cell update") }
        } else Button(onClick = viewModel::start, enabled = missingPermissions.isEmpty()) { Text("Start cellular collection") }
        Text(state.status)
        state.error?.let { DiagnosticNotice("CELL", it) }
        state.request?.let {
            if (it.status == CellRequestStatus.LOCAL_COOLDOWN || it.status == CellRequestStatus.TIMED_OUT || it.status == CellRequestStatus.MODEM_ERROR)
                DiagnosticNotice("CELL", "${it.status.name}: ${it.message}")
            else Text(it.message)
        }
        Text("60-second sessions; refresh requests at least 30 seconds apart. Android may rate-limit or return cache. Stopping unregisters Atlas callbacks; it cannot cancel a modem request already submitted.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = viewModel::clear, enabled = !state.collecting) { Text("Clear cellular history") }
    }
    item {
        Text("Subscription context", style = MaterialTheme.typography.titleLarge)
        Text(state.scopeMessage)
        if (state.subscriptions.isEmpty()) Text("No active slots loaded. Start with permission to inspect availability.")
        state.subscriptions.forEach { subscription ->
            FilterChip(selected = state.selectedId == subscription.id, onClick = { viewModel.select(subscription.id) },
                label = { Text("${if (subscription.slot >= 0) "Slot ${subscription.slot + 1}" else "Slot unavailable"} · local subscription ${subscription.id}") })
        }
        Text("Selecting a slot stops collection. Start again explicitly. Slot IDs are local context, not subscriber credentials.", style = MaterialTheme.typography.bodySmall)
    }
    item {
        Text("Reported network and registration", style = MaterialTheme.typography.titleLarge)
        state.display?.let {
            Text("Reported base technology: ${it.technology}")
            Text("Carrier display: ${it.carrierHint}")
            Text("Display snapshot: ${cellTime(it.receivedAtMillis)}", style = MaterialTheme.typography.bodySmall)
        } ?: Text("Network technology not delivered yet.")
        Text("A 5G display indicator does not prove NR metrics or active dual connectivity.", style = MaterialTheme.typography.bodySmall)
        state.service?.let { service ->
            Text("Voice service: ${service.voiceState}")
            service.registrations.forEach {
                Text("${it.domain} / ${it.transport} · ${it.technology} · registered ${it.registered} · searching ${it.searching} · roaming ${it.roaming}")
            }
            if (service.registrations.isEmpty()) Text("Registration detail unavailable.")
            Text("Service snapshot: ${cellTime(service.receivedAtMillis)}", style = MaterialTheme.typography.bodySmall)
        } ?: Text("Service state not delivered yet.")
    }
    item {
        Text("Subscription signal report", style = MaterialTheme.typography.titleLarge)
        Text("SignalStrength values are not assigned to a specific CellInfo row. LTE and NR are shown separately; missing values stay unavailable.", style = MaterialTheme.typography.bodySmall)
        state.signal?.let { report ->
            Text("Callback subscription context: ${report.subscriptionContext ?: "unavailable"}")
            Text("Received: ${cellTime(report.receivedAtMillis)} · source age ${CellularValues.age(report.sourceElapsedMillis, nowElapsedMillis)?.let { "${it}ms" } ?: "unavailable"}")
            if (report.signals.isEmpty()) Text("No signal strengths reported.")
            report.signals.forEach { signal ->
                Text(signal.rat, style = MaterialTheme.typography.titleMedium)
                Metrics(signal.metrics)
            }
            if (report.signals.none { it.rat == "LTE" && it.metrics.any { metric -> metric.value != null } }) Text("LTE signal values were not supplied in this report.")
            if (report.signals.none { it.rat == "NR" && it.metrics.any { metric -> metric.value != null } }) Text("NR signal values unavailable. This does not establish lack of 5G hardware or network coverage.")
        } ?: Text("No signal callback delivered yet.")
    }
    state.cells?.let { batch ->
        item {
            Text("Reported cells", style = MaterialTheme.typography.titleLarge)
            Text("${batch.rows.size} displayed / ${batch.totalRows} supplied")
            Text(if (batch.delivery == CellDelivery.DEVICE_CACHE) "Device-wide cache: not assigned to a SIM."
                else "Callback/request context: local subscription ${batch.subscriptionContext ?: "unavailable"}. Per-row SIM ownership is not supplied by CellInfo.")
            Text("Source: ${batch.sourceApi} · received ${cellTime(batch.receivedAtMillis)}", style = MaterialTheme.typography.bodySmall)
            Text("Unregistered rows are reported neighbors/observed cells, not a complete neighbor survey. Stale after 30 seconds. Other RAT identities are not normalized.", style = MaterialTheme.typography.bodySmall)
            if (batch.rows.isEmpty()) Text("Android returned an empty cell list. No cells or neighbors can be inferred from it.")
        }
        items(batch.rows.indices.toList(), key = { "cell-${batch.eventId}-$it" }) { index ->
            val row = batch.rows[index]
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${row.rat} · ${CellularValues.connection(row.connectionStatus, row.registered)}", style = MaterialTheme.typography.titleMedium)
                    Text("Cell ID: ${row.cellId ?: "Unavailable"} · PCI: ${row.pci ?: "Unavailable"}")
                    Text("Area code: ${row.areaCode ?: "Unavailable"} · Radio channel: ${row.channel ?: "Unavailable"}")
                    Text("MCC: ${row.mcc ?: "Unavailable"} · MNC: ${row.mnc ?: "Unavailable"}")
                    Metrics(row.metrics)
                    Text("${batch.freshness(row, nowElapsedMillis)} · source age ${CellularValues.age(row.sourceElapsedMillis, nowElapsedMillis)?.let { "${it}ms" } ?: "unavailable"}")
                    Text("Source elapsed time: ${row.sourceElapsedMillis ?: "unavailable"} ms", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } ?: item { Text("No cell list delivered. Signal callbacks may still supply LTE/NR values without cell identities.") }
    item {
        Text("In-memory report history", style = MaterialTheme.typography.titleLarge)
        Text("${state.history.size}/64 cell reports · ${state.signalHistory.size}/128 signal reports retained. Repeated identical cell rows/context do not add history.")
        state.history.take(8).forEach { report ->
            Text("${cellTime(report.receivedAtMillis)} · ${report.rows.size} rows · ${if (report.delivery == CellDelivery.DEVICE_CACHE) "device-wide cache" else "subscription context ${report.subscriptionContext}"}", style = MaterialTheme.typography.bodySmall)
        }
        state.signalHistory.take(8).forEach { report ->
            Text("${cellTime(report.receivedAtMillis)} · subscription ${report.subscriptionContext ?: "unavailable"} · ${report.signals.joinToString { signal -> "${signal.rat}: ${signal.metrics.joinToString { metric -> "${metric.name} ${metric.value ?: "unavailable"} ${metric.unit}" }}" }}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Metrics(metrics: List<CellularMetric>) {
    metrics.forEach { Text("${it.name}: ${it.value?.let { value -> "$value ${it.unit}" } ?: "Unavailable"}") }
}
private fun cellTime(millis: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(millis))
