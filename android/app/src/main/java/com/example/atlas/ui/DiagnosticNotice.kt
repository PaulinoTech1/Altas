package com.example.atlas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** App codes describe a failure class. An OS status is included only when Android supplied one. */
data class DiagnosticDescription(val code: String, val title: String, val nextStep: String, val warning: Boolean = false)

object DiagnosticCatalog {
    fun describe(area: String, message: String): DiagnosticDescription {
        val lower = message.lowercase()
        val osStatus = Regex("(?:gatt status|status)\\s+(\\d+)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)
        val scanStatus = Regex("ble scan failed:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)
        val cellStatus = Regex("platform code\\s+(\\d+)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)
        return when (area) {
            "BLE" -> when {
                scanStatus != null -> DiagnosticDescription("ATLAS-BLE-OS-$scanStatus", "Android rejected the BLE scan", "Wait before retrying. Check Bluetooth and Nearby devices; Android may limit repeated scans.")
                "cooldown" in lower -> DiagnosticDescription("ATLAS-BLE-COOLDOWN", "Scan restart is cooling down", "Wait for the displayed countdown, then start one new scan.")
                "permission" in lower || "denied" in lower || "revoked" in lower -> DiagnosticDescription("ATLAS-BLE-PERMISSION", "Bluetooth access denied", "Review Nearby devices and precise location in Android settings.")
                else -> DiagnosticDescription("ATLAS-BLE-OPERATION", "BLE scan could not continue", "Check Bluetooth and location services, then try a new bounded scan.")
            }
            "GATT" -> when {
                osStatus != null -> DiagnosticDescription("ATLAS-GATT-OS-$osStatus", "GATT operation failed", "Check the peripheral's connection and authorization. The numeric status came from Android; it does not identify a unique cause.")
                "timed out" in lower -> DiagnosticDescription("ATLAS-GATT-TIMEOUT", "Peripheral did not respond", "Move the authorized peripheral closer and reconnect explicitly.")
                "permission" in lower || "denied" in lower -> DiagnosticDescription("ATLAS-GATT-PERMISSION", "GATT access denied", "Review Nearby devices in Android settings, then reconnect.")
                else -> DiagnosticDescription("ATLAS-GATT-OPERATION", "GATT operation could not complete", "Check that the selected peripheral is reachable and supports the requested operation.")
            }
            "WIFI" -> when {
                "local_rate_limit" in lower -> DiagnosticDescription("ATLAS-WIFI-LOCAL-RATE", "Scan request paused by Atlas", "Wait for the local interval before requesting another scan.", true)
                "os_rejected" in lower -> DiagnosticDescription("ATLAS-WIFI-OS-REJECTED", "Android rejected the scan request", "Keep the survey active and wait for platform results. Android may throttle further requests.", true)
                "cached" in lower || "no updated" in lower -> DiagnosticDescription("ATLAS-WIFI-CACHED", "Results may be stale", "Check each measurement's age. Android did not supply an updated scan in this session.", true)
                "thrott" in lower || "cooldown" in lower -> DiagnosticDescription("ATLAS-WIFI-RATE", "Wi-Fi scan is rate limited", "Wait before requesting another scan; Android controls the scan schedule.", true)
                "permission" in lower || "denied" in lower || "revoked" in lower -> DiagnosticDescription("ATLAS-WIFI-PERMISSION", "Wi-Fi survey access denied", "Review precise location and enabled location services in Android settings.")
                else -> DiagnosticDescription("ATLAS-WIFI-OPERATION", "Wi-Fi survey could not continue", "Check Wi-Fi and location services, then start a new survey.")
            }
            "CELL" -> when {
                "local_cooldown" in lower -> DiagnosticDescription("ATLAS-CELL-LOCAL-RATE", "Cell update paused by Atlas", "Wait for the local interval before requesting another cell update.", true)
                cellStatus != null -> DiagnosticDescription("ATLAS-CELL-OS-$cellStatus", "Android reported a cell update error", "Review the platform code and retry later. The code alone does not identify a unique modem cause.")
                "timed_out" in lower -> DiagnosticDescription("ATLAS-CELL-UPDATE-TIMEOUT", "Cell update timed out", "The modem did not return a fresh update in time. Existing callback measurements may still be available.", true)
                "modem_error" in lower -> DiagnosticDescription("ATLAS-CELL-UPDATE", "Cell update unavailable", "Review the reported status and try one new explicit request later.")
                "permission" in lower || "denied" in lower || "revoked" in lower -> DiagnosticDescription("ATLAS-CELL-PERMISSION", "Cellular access denied", "Review Phone status and precise location permissions.")
                "unsupported" in lower -> DiagnosticDescription("ATLAS-CELL-UNSUPPORTED", "Telephony operation unsupported", "Use the metrics Android does report. This public API may be unavailable on this device.")
                else -> DiagnosticDescription("ATLAS-CELL-OPERATION", "Cellular collection could not continue", "Check the selected SIM and network state, then start a new session.")
            }
            "NFC" -> when {
                "hex" in lower || ("apdu" in lower && ("invalid" in lower || "length" in lower)) -> DiagnosticDescription("ATLAS-NFC-APDU-INPUT", "APDU input is invalid", "Review the command's hex bytes before sending it to an authorized tag.")
                "iso-dep" in lower && ("support" in lower || "scan" in lower) -> DiagnosticDescription("ATLAS-NFC-ISO-DEP", "Compatible tag needed", "Present an authorized ISO-DEP tag while reader mode is active.")
                "ndef" in lower && "failed" in lower -> DiagnosticDescription("ATLAS-NFC-NDEF-IO", "NDEF read failed", "Keep the authorized tag steady. The tag may have left the field or rejected the read.")
                "exchange failed" in lower || "tag inspection failed" in lower -> DiagnosticDescription("ATLAS-NFC-TAG-IO", "Tag communication failed", "Hold the authorized tag steady near the antenna and retry the explicit operation.")
                else -> DiagnosticDescription("ATLAS-NFC-READER", "NFC reader could not continue", "Check that NFC is enabled and keep Atlas in the foreground.")
            }
            "STORAGE" -> when {
                "document picker" in lower -> DiagnosticDescription("ATLAS-STORAGE-PICKER", "Document picker unavailable", "Choose another local document provider when Android makes one available.")
                "export" in lower -> DiagnosticDescription("ATLAS-STORAGE-EXPORT", "Export may be incomplete", "Check the chosen document before sharing it. Retry a redacted export if needed.")
                "save failed" in lower -> DiagnosticDescription("ATLAS-STORAGE-SAVE", "Save failed", "Retry saving the current session before leaving the app. Unsaved session history is temporary.")
                "preferences" in lower -> DiagnosticDescription("ATLAS-STORAGE-PREFERENCES", "Preferences update failed", "Review the selected retention limits, then retry.")
                "retention" in lower -> DiagnosticDescription("ATLAS-STORAGE-RETENTION", "Retention check failed", "Review saved history before retrying retention.")
                "read saved telemetry" in lower -> DiagnosticDescription("ATLAS-STORAGE-HISTORY", "Saved history could not load", "Keep Atlas open and retry the History view.")
                else -> DiagnosticDescription("ATLAS-STORAGE-OPERATION", "Saved telemetry operation failed", "Keep the app open and retry. Existing records may still be present.")
            }
            else -> DiagnosticDescription("ATLAS-STATE-CHECK", "Capability check failed", "Refresh capabilities. Previously displayed states may be stale.")
        }
    }
}

@Composable
fun DiagnosticNotice(area: String, message: String, modifier: Modifier = Modifier) {
    val diagnostic = remember(area, message) { DiagnosticCatalog.describe(area, message) }
    var expanded by remember(area, message) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().testTag("diagnostic-${area.lowercase()}"),
        color = if (diagnostic.warning) colors.tertiaryContainer else colors.errorContainer,
        contentColor = if (diagnostic.warning) colors.onTertiaryContainer else colors.onErrorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Icon + spoken prefix so warning vs error is not conveyed by container color alone.
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (diagnostic.warning) AtlasIcons.Warning else AtlasIcons.Error,
                    contentDescription = if (diagnostic.warning) "Warning:" else "Error:", modifier = Modifier.size(20.dp))
                Text(diagnostic.title, style = MaterialTheme.typography.titleMedium)
            }
            SelectionContainer { Text(diagnostic.code, style = MaterialTheme.typography.labelLarge) }
            Text(diagnostic.nextStep, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide diagnostic details" else "Diagnostic details") }
            if (expanded) SelectionContainer {
                Text("Source: $area\nReported: ${message.take(500)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
