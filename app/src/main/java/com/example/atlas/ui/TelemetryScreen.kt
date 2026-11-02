package com.example.atlas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.atlas.telemetry.*
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

fun LazyListScope.storageStatus(state: TelemetryUiState) {
    item(key = "storage-status") {
        ItemColumn { StorageStatusLines(state) }
    }
}

@Composable
private fun StorageStatusLines(state: TelemetryUiState) {
    Text("Saved records: ${state.count}", style = MaterialTheme.typography.titleMedium)
    if (!state.loaded && state.error == null) Explanation("Opening private telemetry storage…")
    state.message?.let { StatusText(it) }
    state.error?.let { DiagnosticNotice("STORAGE", it) }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

/**
 * B4: compact header (count, Save, one-line notice), then search and filters, then records under sticky day headers as
 * two-line rows that expand in place. Paging repeats after the list. "Apply retention now" moved to Settings.
 */
fun LazyListScope.telemetryHistory(
    state: TelemetryUiState, rows: List<Measurement>, vm: TelemetryViewModel, unsavedSummary: String,
    onSave: () -> Unit, openOverview: () -> Unit, openExport: () -> Unit
) {
    item(key = "history-header") {
        SectionCard {
            StorageStatusLines(state)
            if (state.count == 0) Text("No measurements collected")
            // Never disabled just because nothing is new: a repeat save is harmless (de-duplicated) and tests rely on it.
            Button(onClick = onSave, enabled = state.loaded && !state.busy) { Text("Save session measurements") }
            Explanation("Saves every radio's temporary history. $unsavedSummary.")
            ExpandableNotice("Session measurements are temporary until you save them.",
                "Radio histories are temporary until explicitly saved. Saving does not start collection. GATT payloads are not persisted in this phase.")
        }
    }
    if (state.loaded && state.count == 0) {
        item(key = "history-empty") {
            ItemColumn {
                SectionTitle("Nothing saved yet")
                Explanation("Open a radio from Overview, start and stop a session, then save it here or with Save to History on that screen.")
                OutlinedButton(onClick = openOverview) { Text("Open Overview") }
            }
        }
        return
    }
    item(key = "history-filter") {
        ItemColumn {
            HistoryFilter(vm)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Explanation("Up to 200 records per page, newest first.")
                TextButton(onClick = openExport) { Text("Export…") }
            }
            HistoryPager(vm, rows)
            if (rows.isEmpty() && state.loaded) Explanation("No saved records match this search on this page.")
        }
    }
    val zone = ZoneId.systemDefault()
    val today = HistoryGrouping.epochDay(System.currentTimeMillis(), zone)
    HistoryGrouping.byDay(rows, zone) { it.eventTimestampMillis }.forEachIndexed { index, group ->
        stickyHeader(key = "day-${group.epochDay}-$index", contentType = "day-header") { _: Int ->
            // Opaque so rows scrolling beneath never show through.
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Text(HistoryGrouping.dayLabel(group.epochDay, today, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(group.items.first().eventTimestampMillis))),
                    Modifier.padding(vertical = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        items(group.items, key = { it.eventId }, contentType = { "record" }) { row -> HistoryRow(row) }
    }
    if (rows.isNotEmpty()) item(key = "history-pager-bottom") { HistoryPager(vm, rows) }
}

@Composable
private fun HistoryPager(vm: TelemetryViewModel, rows: List<Measurement>) {
    val page by vm.historyPage.collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text("History page $page")
        TextButton(onClick = { vm.page(-1) }, enabled = page > 1) { Text("Previous page") }
        TextButton(onClick = { vm.page(1) }, enabled = rows.size == 200 && page < 250) { Text("Next page") }
    }
}

/** Two lines: "Wi-Fi · rssi — -61 dBm" and "6:51:03 PM · Measured". Tap expands the full inspect details in place. */
@Composable
private fun HistoryRow(row: Measurement) {
    var expanded by rememberSaveable(row.eventId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = if (expanded) "Hide record details" else "Inspect record") { expanded = !expanded },
        tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val value = row.value?.let { "$it ${row.units.orEmpty()}".trim() }
                ?: if (row.collectionStatus == "UNAVAILABLE") "Unavailable" else "Unavailable (${UiText.collectionStatus(row.collectionStatus).lowercase()})"
            Text("${UiText.radioTitle(row.radioType)} · ${row.measurementType} — $value", style = MaterialTheme.typography.titleSmall)
            Text("${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(row.eventTimestampMillis))} · ${UiText.collectionStatus(row.collectionStatus)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(UiText.recordSummary(row.collectionStatus, row.freshness, row.permissionState))
                Text(DateFormat.getDateTimeInstance().format(Date(row.eventTimestampMillis)))
                Text("Source: ${row.sourceApi}", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun HistoryFilter(vm: TelemetryViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var radio by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(radio, query) { vm.filter(radio, query) }
    OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, label = { Text("Search measurement or status") }, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to "All radios", "BLUETOOTH" to "Bluetooth", "WIFI" to "Wi-Fi", "CELLULAR" to "Cellular", "NFC" to "NFC").forEach { (value, title) ->
            FilterChip(selected = radio == value, leadingIcon = chipCheck(radio == value), onClick = { radio = value }, label = { Text(title) })
        }
    }
}

/**
 * B5: Settings in sections: Export, Retention (with a confirmation before anything is deleted), Permissions, About.
 */
fun LazyListScope.storageSettings(state: TelemetryUiState, vm: TelemetryViewModel, onExport: (Boolean) -> Unit, openAppSettings: () -> Unit) {
    storageStatus(state)
    item(key = "settings-export") { ExportSection(state, vm, onExport) }
    item(key = "settings-retention") { RetentionSection(state, vm) }
    item(key = "settings-permissions") {
        SectionCard {
            SectionTitle("Permissions")
            Explanation("Review or change what Atlas may access in Android settings.")
            TextButton(onClick = openAppSettings) { Text("App permission settings") }
        }
    }
}

@Composable
private fun ExportSection(state: TelemetryUiState, vm: TelemetryViewModel, onExport: (Boolean) -> Unit) {
    // Local until "Use as default" is pressed: toggling only affects the next export (the export test toggles it).
    var redact by rememberSaveable(state.preferences.redactExport) { mutableStateOf(state.preferences.redactExport) }
    var confirmFullExport by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        SectionTitle("Private telemetry storage")
        ExpandableNotice("Saved identifiers stay in the app sandbox; exported copies leave it.",
            "Saved identifiers stay in the app sandbox. Backup is disabled. Exports are copied to your chosen provider and are no longer governed by app retention.")
        // The whole row is the switch (label first, Switch last) so the label is clickable and TalkBack reads one control.
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = redact, enabled = !state.busy && state.loaded, role = Role.Switch, onValueChange = { redact = it })
                .testTag("export-redaction")
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Redact identifiers and all metadata in exports", Modifier.weight(1f))
            Switch(checked = redact, onCheckedChange = null, enabled = !state.busy && state.loaded)
        }
        if (!redact) Text("Full exports include observed device/network/cell identifiers, names and advertisement metadata. Share only intentionally.", color = MaterialTheme.colorScheme.error)
        if (state.loaded && redact != state.preferences.redactExport) {
            TextButton(onClick = { vm.setRedactExportDefault(redact) }, enabled = !state.busy) { Text("Use this choice by default") }
        }
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
        ExpandableNotice("Exports include every saved record, regardless of the History filter.",
            "Export includes all saved records after retention, regardless of the History filter. Redaction does not anonymize signal patterns or timestamps.")
    }
}

@Composable
private fun RetentionSection(state: TelemetryUiState, vm: TelemetryViewModel) {
    val current = state.preferences
    var days by rememberSaveable(current.retentionDays) { mutableIntStateOf(current.retentionDays) }
    var maximum by rememberSaveable(current.maximumRows) { mutableIntStateOf(current.maximumRows) }
    // The limits awaiting confirmation; null = no dialog. Nothing is deleted until the user confirms.
    var pendingDays by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingMaximum by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingIsChange by rememberSaveable { mutableStateOf(false) }
    val changed = days != current.retentionDays || maximum != current.maximumRows
    SectionCard {
        SectionTitle("Retention")
        // Applied values only; pending chip selections are labelled as such (finding 07).
        Text("Current: ${UiText.days(current.retentionDays)} · ${UiText.records(current.maximumRows)}", style = MaterialTheme.typography.titleSmall)
        Text("Keep for", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 7, 30).forEach { value -> FilterChip(selected = days == value, leadingIcon = chipCheck(days == value), onClick = { days = value }, label = { Text(UiText.days(value)) }) }
        }
        Text("Max records", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1000, 10000, 50000).forEach { value -> FilterChip(selected = maximum == value, leadingIcon = chipCheck(maximum == value), onClick = { maximum = value }, label = { Text(UiText.records(value)) }) }
        }
        if (changed) Explanation("New: ${UiText.days(days)} · ${UiText.records(maximum)} (not applied yet)")
        ExpandableNotice("Applying retention permanently removes older and excess saved records.",
            "Applying retention permanently removes older/excess saved records. Temporary radio histories remain until cleared or the app process ends.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { pendingDays = days; pendingMaximum = maximum; pendingIsChange = true },
                enabled = changed && state.loaded && !state.busy) { Text("Apply retention limits") }
            TextButton(onClick = { pendingDays = current.retentionDays; pendingMaximum = current.maximumRows; pendingIsChange = false },
                enabled = state.loaded && !state.busy) { Text("Apply retention now") }
        }
    }
    val confirmDays = pendingDays
    val confirmMaximum = pendingMaximum
    if (confirmDays != null && confirmMaximum != null) {
        val target = StoragePreferences(confirmDays, confirmMaximum, current.redactExport)
        RetentionConfirmDialog(vm, target,
            onConfirm = {
                pendingDays = null; pendingMaximum = null
                if (pendingIsChange) vm.configure(target) else vm.refresh()
            },
            onCancel = { pendingDays = null; pendingMaximum = null })
    }
}

/**
 * B5: "Remove up to N records permanently?" Cancel is the dismiss action (outside tap, Back, and the left button);
 * only the error-coloured confirm button deletes.
 */
@Composable
private fun RetentionConfirmDialog(vm: TelemetryViewModel, target: StoragePreferences, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val preview by produceState<RetentionPreview>(RetentionPreview.Loading, target) {
        value = vm.previewRemoval(target)?.let { RetentionPreview.Count(it) } ?: RetentionPreview.Failed
    }
    val title = when (val p = preview) {
        RetentionPreview.Loading -> "Checking saved records…"
        is RetentionPreview.Count -> if (p.value == 0) "Apply retention?" else "Remove up to ${UiText.records(p.value)} permanently?"
        RetentionPreview.Failed -> "Remove records permanently?"
    }
    val text = when (val p = preview) {
        RetentionPreview.Loading -> "Counting records older than ${UiText.days(target.retentionDays)} or beyond ${UiText.records(target.maximumRows)}."
        is RetentionPreview.Count -> if (p.value == 0) "No saved records are currently outside ${UiText.days(target.retentionDays)} · ${UiText.records(target.maximumRows)}. Future saves follow these limits."
            else "Saved records older than ${UiText.days(target.retentionDays)} or beyond the newest ${UiText.records(target.maximumRows)} are deleted from this device. This cannot be undone. Exported copies are not affected."
        RetentionPreview.Failed -> "Atlas could not count the affected records. Applying deletes saved records older than ${UiText.days(target.retentionDays)} or beyond the newest ${UiText.records(target.maximumRows)}. This cannot be undone."
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = preview != RetentionPreview.Loading,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(if ((preview as? RetentionPreview.Count)?.value == 0) "Apply" else "Remove records")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private sealed interface RetentionPreview {
    data object Loading : RetentionPreview
    data object Failed : RetentionPreview
    data class Count(val value: Int) : RetentionPreview
}
