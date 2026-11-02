package com.example.atlas.ui

import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.atlas.model.PermissionState
import com.example.atlas.model.Availability
import com.example.atlas.model.RadioCapability
import com.example.atlas.model.RadioState
import com.example.atlas.model.RadioType
import com.example.atlas.acquisition.bluetooth.BluetoothViewModel
import com.example.atlas.acquisition.wifi.WifiViewModel
import com.example.atlas.acquisition.cellular.CellularViewModel
import com.example.atlas.acquisition.nfc.NfcViewModel
import com.example.atlas.telemetry.TelemetryViewModel
import com.example.atlas.telemetry.TelemetryNormalizer
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun AtlasApp(
    viewModel: DashboardViewModel,
    bluetooth: BluetoothViewModel,
    wifiSurvey: WifiViewModel,
    cellular: CellularViewModel,
    nfc: NfcViewModel,
    telemetry: TelemetryViewModel,
    blePermissionsMissing: List<String>,
    wifiPermissionsMissing: List<String>,
    cellularPermissionsMissing: List<String>,
    onExport: (Boolean) -> Unit,
    onCellularPermissions: () -> Unit,
    onWifiPermissions: () -> Unit,
    onBlePermissions: () -> Unit,
    onRequestPermission: (RadioType) -> Unit,
    onAppSettings: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ble by bluetooth.state.collectAsStateWithLifecycle()
    val gatt by bluetooth.gatt.state.collectAsStateWithLifecycle()
    val wifi by wifiSurvey.state.collectAsStateWithLifecycle()
    val cell by cellular.state.collectAsStateWithLifecycle()
    val nfcState by nfc.state.collectAsStateWithLifecycle()
    val storage by telemetry.state.collectAsStateWithLifecycle()
    val savedHistory by telemetry.history.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("Dashboard") }
    LaunchedEffect(page, storage.loaded) {
        if (storage.loaded && (page == "History" || page == "Settings")) telemetry.refresh()
    }
    val pageListState = key(page) { rememberLazyListState() }
    BackHandler(page != "Dashboard") { page = "Dashboard" }
    val selected = state.radios.firstOrNull { it.type.name == page }
    val bluetoothVisible = selected?.type == RadioType.BLUETOOTH
    DisposableEffect(bluetoothVisible) {
        onDispose { if (bluetoothVisible) bluetooth.stopAll() }
    }
    val wifiVisible = selected?.type == RadioType.WIFI
    DisposableEffect(wifiVisible) {
        onDispose { if (wifiVisible) wifiSurvey.stop("Stopped — Wi-Fi screen inactive") }
    }
    val cellularVisible = selected?.type == RadioType.CELLULAR
    DisposableEffect(cellularVisible) {
        onDispose { if (cellularVisible) cellular.stop("Stopped — Cellular screen inactive") }
    }
    val nfcVisible = selected?.type == RadioType.NFC
    DisposableEffect(nfcVisible) {
        onDispose { if (nfcVisible) nfc.stop("Stopped — NFC screen inactive") }
    }
    val freshnessVisible = wifiVisible || cellularVisible
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
    val wifiNowMicros by produceState(SystemClock.elapsedRealtimeNanos() / 1000, freshnessVisible, lifecycleOwner) {
        if (freshnessVisible) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = SystemClock.elapsedRealtimeNanos() / 1000
                delay(1000) // UI freshness labels only; never polls hardware.
            }
        }
    }
    Scaffold(topBar = { AtlasTopBar() }, bottomBar = { AtlasNavigation(page) { page = it } }) { padding ->
        LazyColumn(
            state = pageListState,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("atlas-content"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(selected?.type?.title ?: if (page == "Dashboard") "Overview" else page, style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
                // History, Settings and Project are peer tabs reached from the nav bar; only radio sub-screens need a way back.
                if (selected != null) TextButton(onClick = { page = "Dashboard" }) { Text("Back to dashboard") }
            }
            // Active sessions are announced on every page (the "Leaving X stops…" copy is a safety notice). When idle, only
            // Overview and the radio screens get a one-line note; History, Settings and Project show nothing.
            val sessionMessage = when {
                nfcState.reading -> "NFC reader active. Leaving NFC stops reader mode."
                cell.collecting -> "Cellular collection active. Leaving Cellular stops app collection."
                wifi.collecting -> "Wi-Fi survey active. Leaving Wi-Fi stops app collection."
                ble.scanning -> "BLE collection active. Leaving Bluetooth stops collection."
                gatt.ready -> "Bluetooth connected. Leaving Bluetooth disconnects the device."
                else -> null
            }
            val idleMessage = when {
                sessionMessage != null -> null
                page == "Dashboard" -> "All collectors idle. Choose a radio to begin."
                else -> when (selected?.type) {
                    RadioType.BLUETOOTH -> "Bluetooth idle. Nothing is collected until you start a BLE scan."
                    RadioType.WIFI -> "Wi-Fi idle. Nothing is collected until you start a survey."
                    RadioType.CELLULAR -> "Cellular idle. Nothing is collected until you start collection."
                    RadioType.NFC -> "NFC idle. Tags are read only after you start the reader."
                    null -> null
                }
            }
            if (sessionMessage != null || idleMessage != null) item(key = "session-banner") {
                AtlasSessionBanner(active = sessionMessage != null, message = sessionMessage ?: idleMessage.orEmpty())
            }
            if (state.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { DiagnosticNotice("STATE", error) } }
            when {
                page == "Project" -> projectContent()
                selected?.type == RadioType.BLUETOOTH -> bluetoothContent(
                    selected, bluetooth, ble, gatt, blePermissionsMissing, onBlePermissions,
                    { onRequestPermission(RadioType.BLUETOOTH) }, onAppSettings
                )
                selected?.type == RadioType.WIFI -> wifiContent(
                    selected, wifiSurvey, wifi, wifiPermissionsMissing, wifiNowMicros,
                    onWifiPermissions, onAppSettings
                )
                selected?.type == RadioType.CELLULAR -> cellularContent(
                    selected, cellular, cell, cellularPermissionsMissing, wifiNowMicros / 1000,
                    onCellularPermissions, onAppSettings
                )
                selected?.type == RadioType.NFC -> nfcContent(selected, nfc, nfcState, activity)
                selected != null -> item {
                    RadioDetails(selected)
                    if (selected.type == RadioType.BLUETOOTH && selected.permission == PermissionState.REQUIRED) {
                        Text("Nearby devices permission is used here only to read Bluetooth enabled state. Denial leaves state unknown.")
                        Button(onClick = { onRequestPermission(selected.type) }) { Text("Check Bluetooth state") }
                    }
                    OutlinedButton(onClick = viewModel::refresh, enabled = !state.refreshing) { Text("Refresh state") }
                    TextButton(onClick = onAppSettings) { Text("App permission settings") }
                }
                page == "History" -> telemetryHistory(storage, savedHistory, telemetry) {
                    // Capture immutable session snapshots before dispatching normalization off the UI thread.
                    val bleSnapshot = ble.history
                    val wifiSnapshot = wifi.history + wifi.rows
                    val connectionSnapshot = wifi.connections
                    val cellSnapshot = cell.history + listOfNotNull(cell.cells)
                    val signalSnapshot = cell.signalHistory + listOfNotNull(cell.signal)
                    val nfcSnapshot = nfcState.tags
                    val errors = listOf(Triple("BLUETOOTH", ble.error, blePermissionsMissing), Triple("WIFI", wifi.error, wifiPermissionsMissing), Triple("CELLULAR", cell.error, cellularPermissionsMissing))
                    val savedAt = System.currentTimeMillis()
                    telemetry.save {
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
                page == "Settings" -> {
                    storageSettings(storage, telemetry, onExport)
                    item {
                        var showDetails by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { showDetails = !showDetails }) { Text("Device & collection details") }
                        if (showDetails) {
                            Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                            Text("Minimum API 31 · Target API 37 · Development preview")
                            Text("No background collector, Internet permission, cloud dependency or network listener. App backup is disabled.")
                            Text("BLE controls are on the Bluetooth screen. Temporary history is bounded to 256 addresses and 1,024 observations. Save from History to retain measurements.")
                            Text("Wi-Fi uses explicit 60-second foreground sessions, bounded 1,024-sample history and scan rate limits. Stop cancels Atlas collection; an OS Wi-Fi scan already requested cannot be cancelled by public APIs.")
                            Text("Cellular uses explicit 60-second sessions, 64 cell-report and 128 signal-report history limits. No protected subscriber identifiers are requested.")
                            Text("NFC reader mode uses explicit 60-second foreground sessions. Up to 32 tag reports and 20 APDU exchanges remain in memory; APDU payloads are never saved to telemetry.")
                            TextButton(onClick = onAppSettings) { Text("App permission settings") }
                        }
                    }
                }
                else -> {
                    item {
                        Text("Choose a radio", style = MaterialTheme.typography.titleLarge)
                        Text("Review permissions, start a session, then stop. Open History to save your measurements.", style = MaterialTheme.typography.bodyMedium)
                        Text(when {
                            storage.error != null -> "Saved history unavailable. Open History for details."
                            !storage.loaded -> "Opening saved history…"
                            else -> "${storage.count} records saved on this device"
                        }, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    items(state.radios.sortedBy { it.type == RadioType.NFC }, key = { it.type }) { radio ->
                        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(radio.type.title, style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = { page = radio.type.name }) { Text("Open ${radio.type.title}") }
                                }
                                val accessReady = when (radio.type) {
                                    RadioType.BLUETOOTH -> blePermissionsMissing.isEmpty()
                                    RadioType.WIFI -> wifiPermissionsMissing.isEmpty()
                                    RadioType.CELLULAR -> cellularPermissionsMissing.isEmpty()
                                    RadioType.NFC -> true
                                }
                                val ready = radio.availability == Availability.AVAILABLE && accessReady &&
                                    radio.state !in setOf(RadioState.DISABLED, RadioState.ERROR, RadioState.PERMISSION_REQUIRED)
                                Text(when {
                                    radio.availability == Availability.UNKNOWN -> "Hardware not checked"
                                    radio.availability == Availability.UNAVAILABLE -> "Hardware unavailable"
                                    radio.state == RadioState.DISABLED -> "Turn on radio to collect"
                                    ready -> "Ready to collect"
                                    else -> "Permission needed"
                                }, style = MaterialTheme.typography.labelLarge,
                                    color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                // NOT_QUERIED is expected on the Overview (e.g. Cellular reads state only on its own screen).
                                val stateLabel = if (radio.state == RadioState.NOT_QUERIED)
                                    "${radio.type.title} state is read when you open ${radio.type.title}" else radio.state.label
                                Text("Hardware: ${radio.availability.label} · $stateLabel", style = MaterialTheme.typography.bodySmall)
                                val permissionLabel = when (radio.type) {
                                    RadioType.BLUETOOTH -> if (blePermissionsMissing.isEmpty()) "Survey access granted" else "Survey permissions needed"
                                    RadioType.WIFI -> if (wifiPermissionsMissing.isEmpty()) "Survey access granted" else "Precise location needed"
                                    RadioType.CELLULAR -> if (cellularPermissionsMissing.isEmpty()) "Diagnostic access granted" else "Phone status / location needed"
                                    else -> radio.permission.label
                                }
                                Text("Permission: $permissionLabel", style = MaterialTheme.typography.bodySmall)
                                val collectionLabel = when (radio.type) {
                                    RadioType.BLUETOOTH -> if (ble.scanning) "Scanning" else "Idle"
                                    RadioType.WIFI -> if (wifi.collecting) "Surveying" else "Idle"
                                    RadioType.CELLULAR -> if (cell.collecting) "Collecting" else "Idle"
                                    RadioType.NFC -> if (nfcState.reading) "Reading" else "Idle"
                                }
                                val lastMeasurement = when (radio.type) {
                                    RadioType.BLUETOOTH -> ble.history.firstOrNull()?.observedAtMillis
                                    RadioType.WIFI -> (wifi.rows.map { it.observedAtMillis } + wifi.connections.map { it.observedAtMillis }).maxOrNull()
                                    RadioType.CELLULAR -> listOfNotNull(cell.cells?.receivedAtMillis, cell.signal?.receivedAtMillis).maxOrNull()
                                    RadioType.NFC -> nfcState.tags.firstOrNull()?.observedAtMillis
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Text("Collection: $collectionLabel", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                LastMeasurementLine(lastMeasurement)
                                radio.error?.let { DiagnosticNotice("STATE", it) }
                            }
                        }
                    }
                    item {
                        OutlinedButton(onClick = viewModel::refresh, enabled = !state.refreshing) { Text("Refresh capabilities") }
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
        radio.error?.let { DiagnosticNotice("STATE", it) }
        HorizontalDivider()
        Text("Source: ${radio.sourceApi}", style = MaterialTheme.typography.bodySmall)
        Text("State checked: ${timeLabel(radio.checkedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
        Text("Last measurement: ${timeLabel(radio.lastMeasurementEpochMillis)}")
        Text(when (radio.type) {
            RadioType.BLUETOOTH -> "BLE discovery, advertisement history and authorized GATT interactions use public Android APIs. Raw HCI is outside scope."
            RadioType.WIFI -> "Surveying is subject to Android location requirements and scan throttling. Monitor mode and injection are unsupported."
            RadioType.CELLULAR -> "LTE/NR metrics and neighboring cells depend on public Android APIs, permissions and network reporting. Protected subscriber identifiers are excluded."
            RadioType.NFC -> "Foreground tag reading and explicit APDU commands require compatible authorized tags. Secure-element credential extraction is unsupported."
        })
    }
}

/** "Last measurement: 6:51 PM (3 min ago)"; the relative part refreshes every 30 s without recomposing the page. */
@Composable
private fun LastMeasurementLine(epochMillis: Long?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(System.currentTimeMillis(), epochMillis, lifecycleOwner) {
        if (epochMillis != null) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(30_000) // Label refresh only; never polls hardware.
            }
        }
    }
    val formatted = epochMillis?.let {
        val sameDay = DateUtils.isToday(it)
        (if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)).format(Date(it))
    }
    Text(UiText.lastMeasurement(epochMillis, now, formatted), style = MaterialTheme.typography.bodySmall)
}

private fun timeLabel(epochMillis: Long?): String = epochMillis?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))
} ?: "None"
