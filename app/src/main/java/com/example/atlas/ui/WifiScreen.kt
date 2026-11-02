package com.example.atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.atlas.acquisition.wifi.*
import com.example.atlas.model.RadioCapability
import java.text.DateFormat
import java.util.Date

fun LazyListScope.wifiContent(
    radio: RadioCapability,
    viewModel: WifiViewModel,
    state: WifiUiState,
    missingPermissions: List<String>,
    nowElapsedMicros: Long,
    sessionNotice: String,
    requestPermissions: () -> Unit,
    openSettings: () -> Unit
) {
    val permissionsGranted = missingPermissions.isEmpty()
    radioControlItem(state.collecting) {
        RadioControlCard(
            active = state.collecting, activity = "Surveying",
            startedAtMillis = state.sessionStartedAtMillis, limitMillis = state.sessionLimitMillis,
            counts = "${state.rows.size} access points · ${state.history.size} samples",
            notice = sessionNotice,
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
        SectionCard {
            val permissionLine = "Survey permission: ${if (permissionsGranted) "Granted" else "Precise location needed"}"
            if (permissionsGranted) {
                AccessGrantedRow(permissionLine) {
                    OutlinedButton(onClick = requestPermissions) { Text("Review Wi-Fi permissions") }
                }
            } else {
                Text(permissionLine, style = MaterialTheme.typography.titleSmall)
                ExpandableNotice(WIFI_RATIONALE_SUMMARY, WIFI_RATIONALE, showFull = true)
                OutlinedButton(onClick = requestPermissions) { Text("Review Wi-Fi permissions") }
                TextButton(onClick = openSettings) { Text("App permission settings") }
                PermissionGuidance()
            }
        }
    }
    item { ItemColumn {
        SectionTitle("Survey summary")
        Text("Android scan throttling: ${when (state.osScanThrottlingEnabled) { true -> "Enabled"; false -> "Disabled"; null -> "Not reported" }}")
        OutlinedButton(onClick = viewModel::clear, enabled = !state.collecting) { Text("Clear Wi-Fi history") }
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
        if (state.rows.isEmpty()) Explanation("No access points reported. Cache may be empty, or a scan may be pending, restricted or unsupported.")
    } }
    items(state.rows, key = { "ap-${it.eventId}" }) { row ->
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.ssid ?: "SSID hidden / unavailable", style = MaterialTheme.typography.titleMedium)
                Text("BSSID: ${row.bssid ?: "Unavailable / redacted"}")
                Text("RSSI: ${row.rssiDbm?.let { "$it dBm" } ?: "Unavailable"} · ${row.frequencyMhz?.let { "$it MHz" } ?: "Frequency unavailable"}")
                Text("${WifiValues.band(row.frequencyMhz)} · Channel ${row.channel ?: "unavailable"} · Width ${row.channelWidth}")
                Text("Advertised security: ${row.capabilities?.takeIf { it.isNotBlank() } ?: "Not supplied"}")
                Text("${row.freshness(nowElapsedMicros).label} · Age ${row.ageMillis(nowElapsedMicros)?.let { "${it}ms" } ?: "unavailable"}")
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

private fun wifiTime(millis: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(millis))
