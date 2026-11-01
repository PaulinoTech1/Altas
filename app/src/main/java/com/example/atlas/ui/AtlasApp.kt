package com.example.atlas.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.atlas.model.PermissionState
import com.example.atlas.model.RadioCapability
import com.example.atlas.model.RadioType
import com.example.atlas.acquisition.bluetooth.BluetoothViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun AtlasApp(
    viewModel: DashboardViewModel,
    bluetooth: BluetoothViewModel,
    blePermissionsMissing: List<String>,
    onBlePermissions: () -> Unit,
    onRequestPermission: (RadioType) -> Unit,
    onAppSettings: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ble by bluetooth.state.collectAsStateWithLifecycle()
    val gatt by bluetooth.gatt.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("Dashboard") }
    BackHandler(page != "Dashboard") { page = "Dashboard" }
    val selected = state.radios.firstOrNull { it.type.name == page }
    val bluetoothVisible = selected?.type == RadioType.BLUETOOTH
    DisposableEffect(bluetoothVisible) {
        onDispose { if (bluetoothVisible) bluetooth.stopAll() }
    }
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("atlas-content"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("ATLAS RF", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(selected?.type?.title ?: page, style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
                Text("Native radio diagnostics", style = MaterialTheme.typography.bodyMedium)
                if (page != "Dashboard") TextButton(onClick = { page = "Dashboard" }) { Text("Back to dashboard") }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Text(if (ble.scanning) "BLE collection active. Leaving Bluetooth stops collection."
                        else "BLE collection inactive. Wi-Fi, cellular and NFC collectors not implemented.",
                        modifier = Modifier.padding(16.dp))
                }
            }
            if (state.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            when {
                selected?.type == RadioType.BLUETOOTH -> bluetoothContent(
                    selected, bluetooth, ble, gatt, blePermissionsMissing, onBlePermissions,
                    { onRequestPermission(RadioType.BLUETOOTH) }, onAppSettings
                )
                selected != null -> item {
                    RadioDetails(selected)
                    if (selected.type == RadioType.BLUETOOTH && selected.permission == PermissionState.REQUIRED) {
                        Text("Nearby devices permission is used here only to read Bluetooth enabled state. Denial leaves state unknown.")
                        Button(onClick = { onRequestPermission(selected.type) }) { Text("Check Bluetooth state") }
                    }
                    OutlinedButton(onClick = viewModel::refresh, enabled = !state.refreshing) { Text("Refresh state") }
                    TextButton(onClick = onAppSettings) { Text("App permission settings") }
                }
                page == "History" -> item {
                    Text(if (ble.history.isEmpty()) "No measurements collected" else "${ble.history.size} BLE observations in memory",
                        style = MaterialTheme.typography.titleLarge)
                    Text("BLE session history is available on the Bluetooth screen. Persistent storage and JSON export arrive in Phase 6.")
                }
                page == "Settings" -> item {
                    Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                    Text("Minimum API 31 · Target API 37")
                    Text("No background collector, Internet permission, cloud dependency or network listener. App backup is disabled.")
                    Text("BLE controls are on the Bluetooth screen. History is bounded to 256 addresses and 1,024 observations in memory; persistent retention arrives in Phase 6.")
                    TextButton(onClick = onAppSettings) { Text("App permission settings") }
                }
                else -> {
                    items(state.radios, key = { it.type }) { radio ->
                        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(radio.type.title, style = MaterialTheme.typography.titleLarge)
                                Text("Hardware: ${radio.availability.label} · State: ${radio.state.label}")
                                Text("Permission: ${radio.permission.label}", style = MaterialTheme.typography.bodyMedium)
                                Text("Collection: ${if (radio.type == RadioType.BLUETOOTH) (if (ble.scanning) "Scanning" else "Idle") else radio.collection.label}", style = MaterialTheme.typography.bodyMedium)
                                Text("Last measurement: ${timeLabel(if (radio.type == RadioType.BLUETOOTH) ble.history.firstOrNull()?.observedAtMillis else radio.lastMeasurementEpochMillis)}", style = MaterialTheme.typography.bodyMedium)
                                radio.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = { page = radio.type.name }) { Text("Open ${radio.type.title}") }
                            }
                        }
                    }
                    item {
                        OutlinedButton(onClick = viewModel::refresh, enabled = !state.refreshing) { Text("Refresh capabilities") }
                        TextButton(onClick = { page = "History" }) { Text("History") }
                        TextButton(onClick = { page = "Settings" }) { Text("Settings & permissions") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioDetails(radio: RadioCapability) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Hardware: ${radio.availability.label}", style = MaterialTheme.typography.titleMedium)
        Text("Permission: ${radio.permission.label}")
        Text("State: ${radio.state.label}")
        Text(radio.detail)
        radio.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text("Source: ${radio.sourceApi}", style = MaterialTheme.typography.bodySmall)
        Text("State checked: ${timeLabel(radio.checkedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
        Text("Last measurement: ${timeLabel(radio.lastMeasurementEpochMillis)}")
        Text(when (radio.type) {
            RadioType.BLUETOOTH -> "BLE discovery, advertisement history and authorized GATT interactions are planned for Phase 2. Raw HCI is outside scope."
            RadioType.WIFI -> "Surveying is subject to Android location requirements and scan throttling. Monitor mode and injection are unsupported."
            RadioType.CELLULAR -> "LTE/NR metrics and neighboring cells depend on public Android APIs, permissions and network reporting. Protected subscriber identifiers are excluded."
            RadioType.NFC -> "Foreground tag reading and explicit APDU commands require compatible authorized tags. Secure-element credential extraction is unsupported."
        })
    }
}

private fun timeLabel(epochMillis: Long?): String = epochMillis?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))
} ?: "None"
