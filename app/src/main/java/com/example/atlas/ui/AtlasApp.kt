package com.example.atlas.ui

import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
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
import com.example.atlas.telemetry.UnsavedData
import kotlinx.coroutines.delay
import java.text.DateFormat

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
    // B8: kept as a State and read through lambdas inside the rows, so the 1 s tick no longer recomposes AtlasApp.
    val wifiNowMicros = produceState(SystemClock.elapsedRealtimeNanos() / 1000, freshnessVisible, lifecycleOwner) {
        if (freshnessVisible) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = SystemClock.elapsedRealtimeNanos() / 1000
                delay(1000) // UI freshness labels only; never polls hardware.
            }
        }
    }
    // B3: what is newer than the last successful save, per radio. Drives the radio-screen cue and the History badge.
    val unsavedCounts = SessionSave.unsavedCounts(ble, wifi, cell, nfcState, storage.savedThroughMillis)
    val saveAll = {
        saveSessionMeasurements(telemetry, bluetooth, wifiSurvey, cellular, nfc,
            blePermissionsMissing, wifiPermissionsMissing, cellularPermissionsMissing)
    }
    fun cue(radio: RadioType) = UnsavedCue(unsavedCounts[radio.name] ?: 0, storage.busy, storage.loaded && storage.error == null)
    Scaffold(topBar = { AtlasTopBar() }, bottomBar = {
        AtlasNavigation(page, historyBadge = unsavedCounts.values.any { it > 0 }) { page = it }
    }) { padding ->
        LazyColumn(
            state = pageListState,
            // B9: edge-to-edge (API 35+) does not resize for the keyboard, so pad the list for the IME beyond the Scaffold insets.
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag("atlas-content"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Pairs {
                    Text(selected?.type?.title ?: if (page == "Dashboard") "Overview" else page, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    // History, Settings and Project are peer tabs reached from the nav bar; only radio sub-screens need a way back.
                    if (selected != null) TextButton(onClick = { page = "Dashboard" }) { Text("Back to dashboard") }
                }
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
            // B1: on a radio screen the same line sits inside that screen's control card, next to Start/Stop.
            val radioNotice = sessionMessage ?: idleMessage.orEmpty()
            val radioScreen = selected?.type != null && page != "Project"
            if (!radioScreen && (sessionMessage != null || idleMessage != null)) item(key = "session-banner") {
                AtlasSessionBanner(active = sessionMessage != null, message = sessionMessage ?: idleMessage.orEmpty())
            }
            if (state.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { DiagnosticNotice("STATE", error) } }
            when {
                page == "Project" -> projectContent()
                selected?.type == RadioType.BLUETOOTH -> bluetoothContent(
                    selected, bluetooth, ble, gatt, blePermissionsMissing, radioNotice, cue(RadioType.BLUETOOTH), saveAll, onBlePermissions,
                    { onRequestPermission(RadioType.BLUETOOTH) }, onAppSettings
                )
                selected?.type == RadioType.WIFI -> wifiContent(
                    selected, wifiSurvey, wifi, wifiPermissionsMissing, { wifiNowMicros.value }, radioNotice, cue(RadioType.WIFI), saveAll,
                    onWifiPermissions, onAppSettings
                )
                selected?.type == RadioType.CELLULAR -> cellularContent(
                    selected, cellular, cell, cellularPermissionsMissing, { wifiNowMicros.value / 1000 }, radioNotice, cue(RadioType.CELLULAR), saveAll,
                    onCellularPermissions, onAppSettings
                )
                selected?.type == RadioType.NFC -> nfcContent(selected, nfc, nfcState, activity, radioNotice, cue(RadioType.NFC), saveAll)
                selected != null -> item {
                    ItemColumn {
                        RadioDetails(selected)
                        if (selected.type == RadioType.BLUETOOTH && selected.permission == PermissionState.REQUIRED) {
                            Explanation("Nearby devices permission is used here only to read Bluetooth enabled state. Denial leaves state unknown.")
                            Button(onClick = { onRequestPermission(selected.type) }) { Text("Check Bluetooth state") }
                        }
                        OutlinedButton(onClick = viewModel::refresh, enabled = !state.refreshing) { Text("Refresh state") }
                        TextButton(onClick = onAppSettings) { Text("App permission settings") }
                    }
                }
                page == "History" -> telemetryHistory(storage, savedHistory, telemetry, UnsavedData.summary(unsavedCounts), saveAll,
                    openOverview = { page = "Dashboard" }, openExport = { page = "Settings" })
                page == "Settings" -> {
                    storageSettings(storage, telemetry, onExport, onAppSettings)
                    // B5: About this device, expandable. App permission settings now has its own Permissions section.
                    item(key = "settings-about") {
                        var showDetails by rememberSaveable { mutableStateOf(false) }
                        ItemColumn {
                            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide device details" else "About this device") }
                            if (showDetails) {
                                Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                                Text("Minimum API 31 · Target API 37 · Development preview")
                                Explanation("No background collector, Internet permission, cloud dependency or network listener. App backup is disabled.")
                                Explanation("BLE controls are on the Bluetooth screen. Temporary history is bounded to 256 addresses and 1,024 observations. Save from History to retain measurements.")
                                Explanation("Wi-Fi uses explicit 60-second foreground sessions, bounded 1,024-sample history and scan rate limits. Stop cancels Atlas collection; an OS Wi-Fi scan already requested cannot be cancelled by public APIs.")
                                Explanation("Cellular uses explicit 60-second sessions, 64 cell-report and 128 signal-report history limits. No protected subscriber identifiers are requested.")
                                Explanation("NFC reader mode uses explicit 60-second foreground sessions. Up to 32 tag reports and 20 APDU exchanges remain in memory; APDU payloads are never saved to telemetry.")
                            }
                        }
                    }
                }
                else -> {
                    // B7: one-line intro; the saved count is a link to History.
                    item(key = "overview-intro") {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            SectionTitle("Choose a radio")
                            when {
                                storage.error != null -> Text("Saved history unavailable", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error)
                                !storage.loaded -> Text("Opening saved history…", style = MaterialTheme.typography.labelLarge)
                                else -> TextButton(onClick = { page = "History" }) { Text(UiText.records(storage.count) + " saved") }
                            }
                        }
                    }
                    items(state.radios.sortedBy { it.type == RadioType.NFC }, key = { it.type }) { radio ->
                        val accessReady = when (radio.type) {
                            RadioType.BLUETOOTH -> blePermissionsMissing.isEmpty()
                            RadioType.WIFI -> wifiPermissionsMissing.isEmpty()
                            RadioType.CELLULAR -> cellularPermissionsMissing.isEmpty()
                            RadioType.NFC -> true
                        }
                        val collecting = when (radio.type) {
                            RadioType.BLUETOOTH -> ble.scanning
                            RadioType.WIFI -> wifi.collecting
                            RadioType.CELLULAR -> cell.collecting
                            RadioType.NFC -> nfcState.reading
                        }
                        val lastMeasurement = when (radio.type) {
                            RadioType.BLUETOOTH -> ble.history.firstOrNull()?.observedAtMillis
                            RadioType.WIFI -> (wifi.rows.map { it.observedAtMillis } + wifi.connections.map { it.observedAtMillis }).maxOrNull()
                            RadioType.CELLULAR -> listOfNotNull(cell.cells?.receivedAtMillis, cell.signal?.receivedAtMillis).maxOrNull()
                            RadioType.NFC -> nfcState.tags.firstOrNull()?.observedAtMillis
                        }
                        OverviewCard(
                            radio = radio,
                            status = RadioStatus.of(radio.availability, radio.state, accessReady, collecting),
                            summary = RadioStatus.summaryLine(radio.availability, radio.state, lastMeasurement?.let(::shortTime)),
                            open = { page = radio.type.name }
                        )
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
/** Time of day for today, short date and time otherwise. */
private fun shortTime(epochMillis: Long): String =
    if (DateUtils.isToday(epochMillis)) TimeFormats.time(epochMillis, DateFormat.SHORT)
    else TimeFormats.dateTime(epochMillis, DateFormat.SHORT, DateFormat.SHORT)

/**
 * B7: compact Overview card. The whole card opens the radio (click label "Open X"); "Open X" also stays a visible,
 * separate TextButton, which is its own click/merge boundary, so DIT's onNodeWithText("Open X") still finds one node.
 */
@Composable
private fun OverviewCard(radio: RadioCapability, status: RadioStatus, summary: String, open: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        Modifier.fillMaxWidth().clip(shape).clickable(onClickLabel = "Open ${radio.type.title}", onClick = open),
        shape = shape, tonalElevation = 1.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(radio.type.title, style = MaterialTheme.typography.titleMedium)
                    StatusChip(status)
                }
                TextButton(onClick = open) { Text("Open ${radio.type.title}") }
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            radio.error?.let { DiagnosticNotice("STATE", it) }
        }
    }
}

@Composable
private fun StatusChip(status: RadioStatus) {
    val (container, content) = when (status.tone) {
        RadioStatus.Tone.GOOD -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        RadioStatus.Tone.ATTENTION -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        RadioStatus.Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            when {
                status == RadioStatus.COLLECTING -> Canvas(Modifier.size(8.dp)) { drawCircle(content) }
                status.tone == RadioStatus.Tone.GOOD -> Icon(AtlasIcons.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                status.tone == RadioStatus.Tone.ATTENTION -> Icon(AtlasIcons.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
                else -> Unit
            }
            Text(status.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun timeLabel(epochMillis: Long?): String = epochMillis?.let {
    TimeFormats.dateTime(it)
} ?: "None"
