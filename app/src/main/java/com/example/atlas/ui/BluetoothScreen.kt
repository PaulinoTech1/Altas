package com.example.atlas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.atlas.acquisition.bluetooth.*
import com.example.atlas.model.RadioCapability
import com.example.atlas.model.PermissionState

fun LazyListScope.bluetoothContent(
    radio: RadioCapability,
    viewModel: BluetoothViewModel,
    state: BluetoothUiState,
    gatt: GattState,
    missingPermissions: List<String>,
    sessionNotice: String,
    unsaved: UnsavedCue,
    onSave: () -> Unit,
    requestPermissions: () -> Unit,
    requestStatePermission: () -> Unit,
    openSettings: () -> Unit
) {
    val permissionsGranted = missingPermissions.isEmpty()
    // B1: control first (fixes "Start BLE scan" sitting below the first screen), then access, results, caveats.
    radioControlItem(state.scanning) {
        RadioControlCard(
            active = state.scanning, activity = "Scanning",
            startedAtMillis = state.sessionStartedAtMillis, limitMillis = state.sessionLimitMillis,
            counts = "${state.devices.size} devices · ${state.receivedCount} observations",
            notice = sessionNotice,
            unsaved = unsaved, onSave = onSave,
            summary = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Hardware: ${radio.availability.label}", style = MaterialTheme.typography.titleMedium)
                    Text("State: ${radio.state.label}", style = MaterialTheme.typography.titleMedium)
                }
            }
        ) {
            BleControls(state, viewModel, permissionsGranted)
            StatusText(state.status)
            state.error?.let { DiagnosticNotice("BLE", it) }
        }
    }
    item(key = "ble-access") {
        SectionCard {
            val missingLabels = buildList {
                if (missingPermissions.any { it.contains("BLUETOOTH_") }) add("Nearby devices")
                if (missingPermissions.any { it.endsWith("_LOCATION") }) add("Precise location")
            }
            val permissionLine = "Survey permissions: ${if (missingLabels.isEmpty()) "Granted" else "Needed: ${missingLabels.joinToString()}"}"
            if (permissionsGranted && radio.permission != PermissionState.REQUIRED) {
                AccessGrantedRow(permissionLine) {
                    OutlinedButton(onClick = requestPermissions) { Text("Review BLE permissions") }
                }
            } else {
                Text(permissionLine, style = MaterialTheme.typography.titleSmall)
                if (radio.permission == PermissionState.REQUIRED) {
                    Button(onClick = requestStatePermission) { Text("Check Bluetooth state") }
                }
                ExpandableNotice(BLE_RATIONALE_SUMMARY, BLE_RATIONALE, showFull = missingPermissions.isNotEmpty())
                OutlinedButton(onClick = requestPermissions) { Text("Review BLE permissions") }
                TextButton(onClick = openSettings) { Text("App permission settings") }
                if (missingPermissions.isNotEmpty()) PermissionGuidance()
            }
        }
    }
    item(key = "ble-results") {
        ItemColumn {
            SectionTitle("BLE collection")
            Text("${state.devices.size} retained addresses · ${state.receivedCount} received observations · ${state.history.size}/1024 history entries")
            OutlinedButton(onClick = viewModel::clearHistory, enabled = !state.scanning) { Text("Clear BLE history") }
        }
    }
    item {
        ItemColumn {
            SectionTitle("GATT: ${gatt.status}")
            gatt.address?.let { Text("Selected connection: $it") }
            gatt.error?.let { DiagnosticNotice("GATT", it) }
            if (gatt.address != null) OutlinedButton(onClick = viewModel.gatt::disconnect) { Text("Disconnect GATT") }
            ExpandableNotice("Connect only to a device you own or are authorized to inspect.",
                "Connect only to a device you own or are authorized to inspect. No app-driven pairing, writes to characteristic values or automatic reconnection. Protected reads may require Android authorization.")
            Pairs {
                gatt.serviceUuids.forEach { Text("Service: $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (gatt.truncated) Explanation("Service/characteristic list capped at 128 services / 512 characteristics.")
        }
    }
    items(gatt.characteristics, key = { "gatt-${it.key}" }) { characteristic ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(characteristic.uuid, style = MaterialTheme.typography.titleSmall)
            Text("Service ${characteristic.serviceUuid}", style = MaterialTheme.typography.bodySmall)
            Text("Read ${characteristic.readable} · Notify ${characteristic.notifiable} · Indicate ${characteristic.indicatable}")
            if (characteristic.readable) OutlinedButton(
                onClick = { viewModel.gatt.read(characteristic.key) }, enabled = gatt.ready && !gatt.busy
            ) { Text("Read characteristic") }
            if (characteristic.notifiable || characteristic.indicatable) {
                val enabled = characteristic.key in gatt.subscribed
                OutlinedButton(onClick = { viewModel.gatt.notifications(characteristic.key, !enabled) },
                    enabled = gatt.ready && !gatt.busy) { Text(if (enabled) "Disable notifications" else "Enable notifications") }
            }
            gatt.values.firstOrNull { it.key == characteristic.key }?.let {
                Text("${it.kind} at ${time(it.timestampMillis)}: ${it.hex.ifEmpty { "(empty value)" }}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    item { SectionTitle("Nearby BLE observations") }
    if (state.devices.isEmpty()) item { Explanation("No BLE devices observed. Start a bounded scan; an empty scan is a valid outcome.") }
    items(state.devices, key = { "device-${it.latest.address}" }) { device ->
        DeviceRow(device, state.selectedAddress == device.latest.address,
            { viewModel.select(if (state.selectedAddress == device.latest.address) null else device.latest.address) },
            { viewModel.connect(device.latest.address) })
    }
    if (state.selectedAddress != null) {
        item { SectionTitle("Selected address history (latest 50)") }
        items(state.history.filter { it.address == state.selectedAddress }.take(50), key = { "event-${it.eventId}" }) {
            Text("${time(it.observedAtMillis)} · ${it.rssiDbm?.let { rssi -> "$rssi dBm" } ?: "RSSI unavailable"} · source ${it.sourceElapsedNanos ?: "unavailable"} ns",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    aboutCollection("ble-about", buildList {
        if (permissionsGranted && radio.permission != PermissionState.REQUIRED) add(BLE_RATIONALE)
        add("No automatic restart. 10-second restart cooldown; Android may impose further limits. Addresses can rotate. This is not lossless packet capture.")
    })
}

private const val BLE_RATIONALE_SUMMARY = "Scanning needs Nearby devices and precise location."
private const val BLE_RATIONALE = "RF surveying uses Nearby devices and precise location. Granting permission does not start collection."

@Composable
private fun BleControls(state: BluetoothUiState, viewModel: BluetoothViewModel, permissionsGranted: Boolean) {
    if (state.scanning) {
        Button(onClick = { viewModel.stop() }) { Text("Stop BLE scan") }
        // Configuration collapses to one line while a scan runs; chips return when idle.
        Text("${state.config.seconds} s · ${state.config.mode.label}", style = MaterialTheme.typography.bodySmall)
    } else {
        Button(onClick = viewModel::start, enabled = permissionsGranted) { Text("Start BLE scan") }
        if (!permissionsGranted) Explanation("Grant survey permissions under Access to start.")
        // B1: one wrapping row instead of three stacked chips, so Start stays on the first screen.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 30).forEach { seconds ->
                FilterChip(selected = state.config.seconds == seconds, leadingIcon = chipCheck(state.config.seconds == seconds),
                    onClick = { viewModel.configure(state.config.copy(seconds = seconds)) }, label = { Text("${seconds}s") })
            }
            BleScanMode.entries.forEach { mode ->
                FilterChip(selected = state.config.mode == mode, leadingIcon = chipCheck(state.config.mode == mode),
                    onClick = { viewModel.configure(state.config.copy(mode = mode)) }, label = { Text(mode.label) })
            }
        }
    }
}

@Composable
private fun DeviceRow(device: BleDeviceHistory, expanded: Boolean, select: () -> Unit, connect: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val event = device.latest
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(event.advertisement.name?.takeIf { it.isNotBlank() } ?: "Unnamed BLE device", style = MaterialTheme.typography.titleMedium)
            Text(event.address)
            Text("${event.rssiDbm?.let { "$it dBm" } ?: "RSSI unavailable"} · ${device.receivedCount} observations")
            Text("First: ${time(device.firstSeenMillis)} · Last: ${time(event.observedAtMillis)}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = select) { Text(if (expanded) "Hide advertisement" else "Inspect advertisement") }
            if (expanded) {
                Text("Source elapsed time: ${event.sourceElapsedNanos ?: "unavailable"} ns")
                Text("Age at arrival: ${event.ageMillis(event.receivedElapsedNanos)?.let { "${it}ms" } ?: "unavailable"}. Retained snapshot; not a live current signal.")
                Text("Connectable: ${event.connectable}")
                Text("Advertised services: ${event.advertisement.serviceUuids.joinToString().ifEmpty { "None reported" }}")
                event.advertisement.manufacturerData.forEach { Text("Manufacturer 0x${it.companyId.toString(16).padStart(4, '0')}: ${it.payloadHex}") }
                Text("Advertised Tx power: ${event.advertisement.txPowerDbm?.let { "$it dBm" } ?: "Not reported"}")
                if (event.advertisement.malformed) Text("Malformed or incomplete AD field detected.")
                if (event.advertisement.truncated) Text("Advertisement truncated by source or size cap.")
                Text("Raw advertisement: ${event.advertisement.rawHex.ifEmpty { "Not supplied" }}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirm = true }, enabled = event.connectable) { Text("Connect to authorized device") }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Authorize GATT connection") },
        text = { Text("Connect to ${event.address}? Continue only if you own this peripheral or have permission to inspect it. Service discovery begins after connection; reads and notification subscriptions require further actions.") },
        confirmButton = { TextButton(onClick = { confirm = false; connect() }) { Text("I have permission — connect") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

private fun time(millis: Long): String = TimeFormats.dateTime(millis)
