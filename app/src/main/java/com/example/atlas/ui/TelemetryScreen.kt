package com.example.atlas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.atlas.telemetry.*
import java.text.DateFormat
import java.util.Date

fun LazyListScope.storageStatus(state: TelemetryUiState) {
    item {
        Text("Saved records: ${state.count}", style = MaterialTheme.typography.titleMedium)
        if (!state.loaded && state.error == null) Text("Opening private telemetry storage…")
        state.message?.let { Text(it) }
        state.error?.let { DiagnosticNotice("STORAGE", it) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

fun LazyListScope.telemetryHistory(state: TelemetryUiState, rows: List<Measurement>, vm: TelemetryViewModel, onSave: () -> Unit) {
    storageStatus(state)
    item {
        Text("Radio histories are temporary until explicitly saved. Saving does not start collection. GATT payloads are not persisted in this phase.")
        Button(onClick = onSave, enabled = state.loaded && !state.busy) { Text("Save session measurements") }
        TextButton(onClick = vm::refresh, enabled = state.loaded && !state.busy) { Text("Apply retention now") }
        Text(if (state.count == 0) "No measurements collected" else "Showing up to 200 matching saved records per page, newest first.")
        HistoryFilter(vm)
        val page by vm.historyPage.collectAsStateWithLifecycle()
        Text("History page $page")
        Row {
            TextButton(onClick = { vm.page(-1) }, enabled = page > 1) { Text("Previous page") }
            TextButton(onClick = { vm.page(1) }, enabled = rows.size == 200 && page < 250) { Text("Next page") }
        }
    }
    items(rows, key = { it.eventId }) { row ->
        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${UiText.radioTitle(row.radioType)} · ${row.measurementType}", style = MaterialTheme.typography.titleMedium)
                Text(row.value?.let { "$it ${row.units.orEmpty()}" }
                    ?: if (row.collectionStatus == "UNAVAILABLE") "Unavailable" else "Unavailable (${UiText.collectionStatus(row.collectionStatus).lowercase()})")
                Text(DateFormat.getDateTimeInstance().format(Date(row.eventTimestampMillis)))
                Text(UiText.recordSummary(row.collectionStatus, row.freshness, row.permissionState))
                Text("Source: ${row.sourceApi}", style = MaterialTheme.typography.bodySmall)
                var expanded by rememberSaveable(row.eventId) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide record details" else "Inspect record") }
                if (expanded) {
                    Text("Event: ${row.eventId}")
                    Text("Radio: ${row.radioType} · status ${row.collectionStatus} · freshness at receipt ${row.freshness} · permission ${row.permissionState}")
                    Text("Interface: ${row.interfaceId ?: "Unavailable"}")
                    Text("Source timestamp: ${row.sourceTimestampMillis ?: "Unavailable"} (${row.sourceTimeBasis})")
                    Text("Received elapsed: ${row.receivedElapsedMillis ?: "Unavailable"} ms · clock scope ${row.bootId ?: "Unavailable"}")
                    Text(row.metadataJson, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun HistoryFilter(vm: TelemetryViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var radio by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(radio, query) { vm.filter(radio, query) }
    OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, label = { Text("Search measurement or status") }, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to "All radios", "BLUETOOTH" to "Bluetooth", "WIFI" to "Wi-Fi", "CELLULAR" to "Cellular", "NFC" to "NFC").forEach { (value, title) ->
            FilterChip(selected = radio == value, onClick = { radio = value }, label = { Text(title) })
        }
    }
}

fun LazyListScope.storageSettings(state: TelemetryUiState, vm: TelemetryViewModel, onExport: (Boolean) -> Unit) {
    storageStatus(state)
    item { SettingsControls(state, vm, onExport) }
}

@Composable
private fun SettingsControls(state: TelemetryUiState, vm: TelemetryViewModel, onExport: (Boolean) -> Unit) {
    var days by rememberSaveable(state.preferences.retentionDays) { mutableIntStateOf(state.preferences.retentionDays) }
    var maximum by rememberSaveable(state.preferences.maximumRows) { mutableIntStateOf(state.preferences.maximumRows) }
    var redact by rememberSaveable(state.preferences.redactExport) { mutableStateOf(state.preferences.redactExport) }
    var confirmFullExport by rememberSaveable { mutableStateOf(false) }
    Text("Private telemetry storage", style = MaterialTheme.typography.titleLarge)
    Text("Saved identifiers stay in the app sandbox. Backup is disabled. Exports are copied to your chosen provider and are no longer governed by app retention.")
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Switch(checked = redact, onCheckedChange = { redact = it }, enabled = !state.busy && state.loaded,
            modifier = Modifier.testTag("export-redaction"))
        Text("Redact identifiers and all metadata in exports")
    }
    if (!redact) Text("Full exports include observed device/network/cell identifiers, names and advertisement metadata. Share only intentionally.", color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = { if (redact) onExport(true) else confirmFullExport = true }, enabled = state.loaded && !state.busy) {
        Text(if (redact) "Export redacted JSON" else "Export JSON including identifiers")
    }
    if (confirmFullExport) {
        AlertDialog(
            onDismissRequest = { confirmFullExport = false },
            title = { Text("Export identifiers?") },
            text = { Text("This copy includes saved Bluetooth addresses, Wi-Fi network identifiers, cell identifiers, names and advertisement metadata. The chosen document provider controls the exported file. Continue only if you intend to share this information.") },
            confirmButton = {
                TextButton(enabled = state.loaded && !state.busy, onClick = {
                    confirmFullExport = false
                    onExport(false)
                }) { Text("Choose export location") }
            },
            dismissButton = { TextButton(onClick = { confirmFullExport = false }) { Text("Cancel") } }
        )
    }
    Text("Export includes all saved records after retention, regardless of the History filter. Redaction does not anonymize signal patterns or timestamps.")
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Retention: ${UiText.days(days)}; maximum ${UiText.count(maximum, "measurement record")}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 7, 30).forEach { value -> FilterChip(selected = days == value, onClick = { days = value }, label = { Text(UiText.days(value)) }) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1000, 10000, 50000).forEach { value -> FilterChip(selected = maximum == value, onClick = { maximum = value }, label = { Text(UiText.records(value)) }) }
    }
    Text("Applying retention permanently removes older/excess saved records. Temporary radio histories remain until cleared or the app process ends.")
    Button(onClick = { vm.configure(StoragePreferences(days, maximum, redact)) }, enabled = state.loaded && !state.busy) { Text("Apply storage preferences") }
}
