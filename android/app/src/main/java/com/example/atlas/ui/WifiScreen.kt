package com.example.atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.atlas.ui.viz.SpectrumMath
import com.example.atlas.ui.viz.WifiSpectrum
import com.example.atlas.ui.viz.ReplayChart
import com.example.atlas.replay.ReplayState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.atlas.acquisition.wifi.*
import com.example.atlas.model.RadioCapability

fun LazyListScope.wifiContent(
    radio: RadioCapability,
    viewModel: WifiViewModel,
    state: WifiUiState,
    tcpScan: TcpScanViewModel,
    tcp: TcpScanUiState,
    lanDiscovery: LanDiscoveryViewModel,
    lan: LanDiscoveryState,
    missingPermissions: List<String>,
    localNetworkPermissionsMissing: List<String>,
    nowElapsedMicros: () -> Long,
    sessionNotice: String,
    unsaved: UnsavedCue,
    onSave: () -> Unit,
    requestPermissions: () -> Unit,
    requestLocalNetworkPermissions: () -> Unit,
    openSettings: () -> Unit,
    replay: ReplayState? = null
) {
    val permissionsGranted = missingPermissions.isEmpty()
    radioControlItem(state.collecting) {
        RadioControlCard(
            active = state.collecting, activity = "Surveying",
            startedAtMillis = state.sessionStartedAtMillis, limitMillis = state.sessionLimitMillis,
            counts = "${state.rows.size} access points · ${state.history.size} samples",
            notice = sessionNotice,
            unsaved = unsaved, onSave = onSave,
            summary = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Hardware: ${radio.availability.label}", style = MaterialTheme.typography.titleMedium)
                    Text("Capability check: ${radio.state.label}", style = MaterialTheme.typography.titleMedium)
                }
            }
        ) {
            if (state.collecting) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { viewModel.stop() }) { Text("Stop Wi-Fi survey") }
                    OutlinedButton(onClick = viewModel::requestScan) { Text("Request Wi-Fi scan") }
                }
            } else {
                Button(onClick = viewModel::start, enabled = permissionsGranted) { Text("Start Wi-Fi survey") }
                if (!permissionsGranted) Explanation("Grant precise location under Access to start.")
            }
            StatusText(state.status)
            state.request?.let {
                if (it.status == WifiRequestStatus.ACCEPTED) Text(it.message)
                else DiagnosticNotice("WIFI", "${it.status.name}: ${it.message}")
            }
            state.error?.let { DiagnosticNotice("WIFI", it) }
        }
    }
    item(key = "wifi-access") {
            val permissionLine = "Survey permission: ${if (permissionsGranted) "Granted" else "Precise location needed"}"
            if (permissionsGranted) {
                AccessGrantedRow(permissionLine) {
                    TextButton(onClick = requestPermissions) { Text("Review Wi-Fi permissions") }
                }
            } else {
                SectionCard {
                    Text(permissionLine, style = MaterialTheme.typography.titleSmall)
                    ExpandableNotice(WIFI_RATIONALE_SUMMARY, WIFI_RATIONALE, showFull = true)
                    OutlinedButton(onClick = requestPermissions) { Text("Review Wi-Fi permissions") }
                    TextButton(onClick = openSettings) { Text("App permission settings") }
                    PermissionGuidance()
                }
            }
    }
    // TCP checks are independent of AP surveying; keep the explicit host tool reachable before the charts.
    item(key = "lan-discovery") {
        LanDiscoveryCard(lanDiscovery, lan, localNetworkPermissionsMissing.isEmpty(), requestLocalNetworkPermissions, replay != null)
    }
    item(key = "tcp-port-scan") {
        TcpPortScanCard(tcpScan, tcp, localNetworkPermissionsMissing.isEmpty(), requestLocalNetworkPermissions, replay != null)
    }
    // V1: spectrum above the text summary and list; tapping a hump selects the same AP the list expands.
    item(key = "wifi-spectrum") {
        if (replay != null) {
            // V5: the chart shows saved data only; the text summary and list below stay live.
            ReplayChart(replay, "Wi-Fi spectrum", { it.hasWifi }) { frame -> WifiSpectrum(frame.wifi, null, {}, replay = true) }
        } else {
            val aps = remember(state.rows, state.connections) { SpectrumMath.fromRows(state.rows, state.connections) }
            WifiSpectrum(aps, state.selectedBssid, onSelect = { ap -> ap.bssid?.let(viewModel::select) })
        }
    }
    item { ItemColumn {
        SectionTitle("Survey summary")
        Text("Android scan throttling: ${when (state.osScanThrottlingEnabled) { true -> "Enabled"; false -> "Disabled"; null -> "Not reported" }}")
        ClearHistoryControl("Clear Wi-Fi history", state.rows.isNotEmpty() || state.history.isNotEmpty() || state.connections.isNotEmpty(),
            state.collecting, unsaved, viewModel::clear)
        Text("${state.rows.size} displayed AP rows / ${state.totalRows} reported · ${state.history.size}/1024 distinct source samples retained")
        Text("${state.updatedBroadcasts} updated platform broadcasts / ${state.scanBroadcasts} total. Broadcasts are shared across apps; an update is not proof this app caused the scan.")
        state.rows.groupingBy { WifiValues.band(it.frequencyMhz) }.eachCount().toSortedMap().forEach { (band, count) -> Text("$band: $count reported rows") }
        Text("AP counts by channel (not channel utilization)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        val channels = state.rows.filter { it.channel != null }.groupingBy { "${WifiValues.band(it.frequencyMhz)} / ${it.channel}" }.eachCount()
        Text(channels.toSortedMap().entries.joinToString(" · ") { "${it.key}: ${it.value}" }.ifEmpty { "No valid channels reported" })
    } }
    item { ItemColumn {
        SectionTitle("Reported Wi-Fi connections")
        ExpandableNotice("Link rates are reported by Android, not measured throughput.",
            "Link rates are reported connection properties, not measured throughput. Connection snapshots are retained after Stop.")
        if (state.connections.isEmpty()) Explanation("No Wi-Fi connection information delivered in this session. No connection, permission redaction, or a pending callback are possible.")
    } }
    items(state.connections, key = { "connection-${it.key}" }) { connection ->
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(connection.ssid ?: "SSID unavailable / redacted", style = MaterialTheme.typography.titleMedium)
                Text("BSSID: ${connection.bssid ?: "Unavailable / redacted"}")
                Text("RSSI: ${connection.rssiDbm?.let { "$it dBm" } ?: "Unavailable"}")
                Text("${connection.frequencyMhz?.let { "$it MHz" } ?: "Frequency unavailable"} · Channel ${connection.channel ?: "unavailable"}")
                Text("Link: ${connection.linkSpeedMbps ?: "unavailable"} Mbps · Tx ${connection.txMbps ?: "unavailable"} / Rx ${connection.rxMbps ?: "unavailable"} Mbps")
                Text("${WifiValues.standard(connection.standard)} · Android validated: ${connection.validated}")
                Text("Snapshot: ${wifiTime(connection.observedAtMillis)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    item { ItemColumn {
        SectionTitle("Access points")
        ExpandableNotice("Rows become stale 30 seconds after their source timestamp.",
            "Source timestamps determine age; rows become stale after 30 seconds. Empty SSIDs may be hidden or redacted. Channel numbers do not establish regional authorization.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WifiApSort.entries.forEach { sort ->
                FilterChip(selected = state.sort == sort, onClick = { viewModel.sortBy(sort) }, label = { Text(sort.label) })
            }
        }
        if (state.rows.isEmpty()) Explanation("No access points reported. Cache may be empty, or a scan may be pending, restricted or unsupported.")
    } }
    // B8: keyed by BSSID so a rescan updates rows in place (eventId changes every scan); StableKeys never duplicates.
    val sortedRows = state.sort.order(state.rows)
    val apKeys = StableKeys.of(sortedRows, { row -> row.bssid?.let { "ap-$it" } }, { row -> "ap-${row.eventId}" })
    items(sortedRows.size, key = { apKeys[it] }) { index ->
        val row = sortedRows[index]
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.ssid ?: "SSID hidden / unavailable", style = MaterialTheme.typography.titleMedium)
                Text("BSSID: ${row.bssid ?: "Unavailable / redacted"}")
                Text("RSSI: ${row.rssiDbm?.let { "$it dBm" } ?: "Unavailable"} · ${row.frequencyMhz?.let { "$it MHz" } ?: "Frequency unavailable"}")
                Text("${WifiValues.band(row.frequencyMhz)} · Channel ${row.channel ?: "unavailable"} · Width ${row.channelWidth}")
                Text("Advertised security: ${row.capabilities?.takeIf { it.isNotBlank() } ?: "Not supplied"}")
                Text("${row.freshness(nowElapsedMicros()).label} · Age ${row.ageMillis(nowElapsedMicros())?.let { "${it}ms" } ?: "unavailable"}")
                Text("Delivered: ${wifiTime(row.observedAtMillis)} · ${row.delivery}", style = MaterialTheme.typography.bodySmall)
                if (row.bssid != null) TextButton(onClick = { viewModel.select(row.bssid) }) { Text("Inspect signal history") }
                if (row.bssid != null && row.bssid == state.selectedBssid) {
                    val samples = state.history.filter { it.bssid == row.bssid }.take(50).asReversed()
                    SectionTitle("Selected AP signal history")
                    SignalHistory(samples)
                    Explanation("Chart: latest 50 actual source observations; list: latest 10. Cache rereads add no samples. Gaps are not filled.")
                    samples.asReversed().take(10).forEach { sample ->
                        Text("${wifiTime(sample.observedAtMillis)} · ${sample.rssiDbm?.let { "$it dBm" } ?: "RSSI unavailable"} · Source ${sample.sourceElapsedMicros ?: "unavailable"} µs", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    aboutCollection("wifi-about", buildList {
        if (permissionsGranted) add(WIFI_RATIONALE)
        add("Sessions stop after 60 seconds. No automatic retries. Requests are spaced by at least 30 seconds and limited to four per two minutes; Android can further restrict scans.")
        add("Stop unregisters Atlas callbacks. Android provides no cancellation API for a hardware scan already requested.")
    })
}

@Composable
private fun LanDiscoveryCard(viewModel: LanDiscoveryViewModel, state: LanDiscoveryState,
                             permissionGranted: Boolean, requestPermission: () -> Unit, replay: Boolean) {
    var authorized by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        SectionTitle("Devices observed on this Wi-Fi")
        Text("Checks up to 253 other private IPv4 addresses on TCP ports ${LanDiscoveryPolicy.ports.joinToString()}, and listens briefly for advertised network services. A service name or role is a device claim, not verified identity. Devices that block these ports, advertise no supported service, or are isolated may be absent.",
            style = MaterialTheme.typography.bodySmall)
        if (!permissionGranted) {
            Text("Local network permission is needed for device discovery and TCP host checks.")
            OutlinedButton(onClick = requestPermission) { Text("Allow local network access") }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = authorized, onCheckedChange = { authorized = it }, enabled = !state.running)
            Text("I own or am authorized to test this Wi-Fi network")
        }
        if (state.running) Button(onClick = viewModel::stop) { Text("Stop device discovery") }
        else Button(onClick = { viewModel.start(); authorized = false },
            enabled = authorized && permissionGranted && !replay) { Text("Find devices") }
        if (replay) Text("Turn off Demo replay to discover live devices.", style = MaterialTheme.typography.bodySmall)
        Text(state.status)
        state.target?.let { Text("Wi-Fi address: ${it.localAddress}/${it.prefixLength} · ${it.hosts.size} other addresses") }
        state.progress?.let { progress ->
            val devices = state.target?.let { LanDeviceEvidence.merge(it, progress.hosts, state.services) }.orEmpty()
            Text("${progress.checked}/${progress.total} addresses checked · ${devices.size} observed")
            if (progress.probeErrors > 0) Text("${progress.probeErrors} TCP probe errors; results may be incomplete.")
            if (!state.running && devices.isEmpty()) Text("No device responded to these checks. This is not a complete network inventory.")
            devices.forEach { device ->
                Text(device.hostnames.firstOrNull() ?: device.address, style = MaterialTheme.typography.titleSmall)
                if (device.hostnames.isNotEmpty()) Text("${device.address} · mDNS hostname: ${device.hostnames.joinToString()}")
                device.networkRole?.let { Text("Network role: $it") }
                if (device.advertisedRoles.isNotEmpty())
                    Text("Advertised roles: ${device.advertisedRoles.joinToString()}")
                else if (device.networkRole == null) Text("Device type: unknown")
                if (device.services.isNotEmpty()) Text("mDNS services: " + device.services.joinToString { "${it.serviceName} (${it.serviceType})" })
                if (device.respondingPorts.isNotEmpty()) Text("TCP connected: ${device.respondingPorts.joinToString()}")
            }
        }
        state.serviceNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { DiagnosticNotice("LAN", it) }
    }
}

@Composable
private fun TcpPortScanCard(viewModel: TcpScanViewModel, state: TcpScanUiState,
                            permissionGranted: Boolean, requestPermission: () -> Unit, replay: Boolean) {
    var host by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("1") }
    var last by rememberSaveable { mutableStateOf("1024") }
    var authorized by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        SectionTitle("TCP ports on one host")
        Text("Connect-only checks over the current Wi-Fi network. One numeric IPv4 target; up to 4,096 ports per run. Open means a TCP connection succeeded. Other outcomes do not prove a port is closed.",
            style = MaterialTheme.typography.bodySmall)
        if (!permissionGranted) OutlinedButton(onClick = requestPermission) { Text("Allow local network access") }
        OutlinedTextField(value = host, onValueChange = { host = it.take(15) }, singleLine = true,
            label = { Text("Target IPv4 address") }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { first = "1"; last = "1024" }, enabled = !state.running) { Text("1–1024") }
            OutlinedButton(onClick = { first = "1"; last = "4096" }, enabled = !state.running) { Text("1–4096") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = first, onValueChange = { first = it.take(5).filter(Char::isDigit) },
                label = { Text("First port") }, singleLine = true)
            OutlinedTextField(value = last, onValueChange = { last = it.take(5).filter(Char::isDigit) },
                label = { Text("Last port") }, singleLine = true)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = authorized, onCheckedChange = { authorized = it }, enabled = !state.running)
            Text("I own or am authorized to test this host")
        }
        if (state.running) Button(onClick = viewModel::stop) { Text("Stop TCP scan") }
        else Button(onClick = {
            viewModel.start(TcpScanRequest(host.trim(), first.toIntOrNull() ?: -1, last.toIntOrNull() ?: -1))
            authorized = false
        }, enabled = authorized && permissionGranted && !replay) { Text("Scan selected host") }
        if (replay) Text("Turn off Demo replay to start a live TCP scan.", style = MaterialTheme.typography.bodySmall)
        Text("${state.status}${state.host?.let { " · $it" } ?: ""}")
        state.error?.let { DiagnosticNotice("TCP", it) }
        state.progress?.let { progress ->
            Text("${progress.tested}/${progress.total} tested · ${progress.openPorts.size} connected · ${progress.noConnection} no connection · ${progress.timedOut} timed out · ${progress.errors} errors")
            if (progress.openPorts.isNotEmpty()) Text("Connected TCP ports: ${progress.openPorts.joinToString()}")
        }
    }
}

private const val WIFI_RATIONALE_SUMMARY = "Wi-Fi surveying can reveal location."
private const val WIFI_RATIONALE = "Wi-Fi surveying can reveal location. Android requires precise location permission and enabled location services."

@Composable
private fun SignalHistory(rows: List<WifiObservation>) {
    val points = rows.filter { it.rssiDbm != null && it.sourceElapsedMicros != null }
    if (points.isEmpty()) { Text("No timestamped RSSI history for this address."); return }
    val minRssi = points.minOf { it.rssiDbm!! }
    val maxRssi = points.maxOf { it.rssiDbm!! }
    Text("${points.size} samples · $minRssi to $maxRssi dBm")
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(100.dp).semantics {
        contentDescription = "Signal history, ${points.size} actual samples, minimum $minRssi and maximum $maxRssi dBm"
    }) {
        val start = points.first().sourceElapsedMicros!!
        val duration = (points.last().sourceElapsedMicros!! - start).coerceAtLeast(1)
        val range = (maxRssi - minRssi + 10).toFloat()
        val coordinates = points.map { point -> Offset(
            if (points.size == 1) size.width / 2 else (point.sourceElapsedMicros!! - start).toFloat() / duration * size.width,
            size.height * (1 - (point.rssiDbm!! - minRssi + 5) / range)
        ) }
        coordinates.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, strokeWidth = 2.dp.toPx()) }
        coordinates.forEach { drawCircle(color, 3.dp.toPx(), it) }
    }
    Text("Time → (source timestamps)", style = MaterialTheme.typography.bodySmall)
}

private fun wifiTime(millis: Long) = TimeFormats.dateTime(millis)
