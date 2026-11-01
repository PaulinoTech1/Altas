package com.example.atlas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import java.text.DateFormat
import java.util.Date

fun LazyListScope.bluetoothContent(
    radio: RadioCapability,
    viewModel: BluetoothViewModel,
    state: BluetoothUiState,
    gatt: GattState,
    missingPermissions: List<String>,
    requestPermissions: () -> Unit,
    requestStatePermission: () -> Unit,
    openSettings: () -> Unit
) {
    item {
        Text("Hardware: ${radio.availability.label}", style = MaterialTheme.typography.titleMedium)
        Text("State: ${radio.state.label}")
        val missingLabels = buildList {
            if (missingPermissions.any { it.contains("BLUETOOTH_") }) add("Nearby devices")
            if (missingPermissions.any { it.endsWith("_LOCATION") }) add("Precise location")
        }
        Text("Survey permissions: ${if (missingLabels.isEmpty()) "Granted" else "Needed: ${missingLabels.joinToString()}"}")
        if (radio.permission == PermissionState.REQUIRED) {
            Button(onClick = requestStatePermission) { Text("Check Bluetooth state") }
        }
        Text("RF surveying uses Nearby devices and precise location. Granting permission does not start collection.")
        OutlinedButton(onClick = requestPermissions) { Text("Review BLE permissions") }
        TextButton(onClick = openSettings) { Text("App permission settings") }
    }
    item {
        Text("BLE collection", style = MaterialTheme.typography.titleLarge)
        BleControls(state, viewModel, missingPermissions.isEmpty())
        Text(state.status)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("${state.devices.size} retained addresses · ${state.receivedCount} received observations · ${state.history.size}/1024 history entries")
        Text("No automatic restart. 10-second restart cooldown; Android may impose further limits. Addresses can rotate. This is not lossless packet capture.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = viewModel::clearHistory, enabled = !state.scanning) { Text("Clear BLE history") }
    }
    item {
        Text("GATT: ${gatt.status}", style = MaterialTheme.typography.titleLarge)
        gatt.address?.let { Text("Selected connection: $it") }
        gatt.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (gatt.address != null) OutlinedButton(onClick = viewModel.gatt::disconnect) { Text("Disconnect GATT") }
        Text("Connect only to a device you own or are authorized to inspect. No app-driven pairing, writes to characteristic values or automatic reconnection. Protected reads may require Android authorization.")
        gatt.serviceUuids.forEach { Text("Service: $it", style = MaterialTheme.typography.bodySmall) }
        if (gatt.truncated) Text("Service/characteristic list capped at 128 services / 512 characteristics.")
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
    item { Text("Nearby BLE observations", style = MaterialTheme.typography.titleLarge) }
    if (state.devices.isEmpty()) item { Text("No BLE devices observed. Start a bounded scan; an empty scan is a valid outcome.") }
    items(state.devices, key = { "device-${it.latest.address}" }) { device ->
        DeviceRow(device, state.selectedAddress == device.latest.address,
            { viewModel.select(if (state.selectedAddress == device.latest.address) null else device.latest.address) },
            { viewModel.connect(device.latest.address) })
    }
    if (state.selectedAddress != null) {
        item { Text("Selected address history (latest 50)", style = MaterialTheme.typography.titleMedium) }
        items(state.history.filter { it.address == state.selectedAddress }.take(50), key = { "event-${it.eventId}" }) {
            Text("${time(it.observedAtMillis)} · ${it.rssiDbm?.let { rssi -> "$rssi dBm" } ?: "RSSI unavailable"} · source ${it.sourceElapsedNanos ?: "unavailable"} ns",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BleControls(state: BluetoothUiState, viewModel: BluetoothViewModel, permissionsGranted: Boolean) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 30).forEach { seconds ->
                FilterChip(selected = state.config.seconds == seconds, enabled = !state.scanning,
                    onClick = { viewModel.configure(state.config.copy(seconds = seconds)) }, label = { Text("${seconds}s") })
            }
        }
        BleScanMode.entries.forEach { mode ->
            FilterChip(selected = state.config.mode == mode, enabled = !state.scanning,
                onClick = { viewModel.configure(state.config.copy(mode = mode)) }, label = { Text(mode.label) })
        }
        if (state.scanning) Button(onClick = { viewModel.stop() }) { Text("Stop BLE scan") }
        else Button(onClick = viewModel::start, enabled = permissionsGranted) { Text("Start BLE scan") }
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

private fun time(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(millis))
