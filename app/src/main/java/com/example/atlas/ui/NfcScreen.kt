package com.example.atlas.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.atlas.acquisition.nfc.NfcUiState
import com.example.atlas.acquisition.nfc.NfcViewModel
import com.example.atlas.model.RadioCapability
import java.text.DateFormat
import java.util.Date

fun LazyListScope.nfcContent(radio: RadioCapability, vm: NfcViewModel, state: NfcUiState, activity: Activity) {
    item {
        var confirmNdef by rememberSaveable { mutableStateOf(false) }
        Text("Hardware: ${radio.availability.label} · ${radio.state.label}", style = MaterialTheme.typography.titleMedium)
        Text("Reader mode operates only while this screen is active. Hold a tag you own or are authorized to inspect near the phone. Metadata-only mode does not request NDEF content.")
        if (state.reading) Button(onClick = { vm.stop() }) { Text("Stop NFC reader") }
        else {
            Button(onClick = { vm.start(activity) }) { Text("Start NFC reader") }
            OutlinedButton(onClick = { confirmNdef = true }) { Text("Read NDEF on an authorized tag") }
        }
        if (confirmNdef) AlertDialog(onDismissRequest = { confirmNdef = false }, title = { Text("Read tag content?") },
            text = { Text("NDEF may contain private data. Scanning a security key such as a YubiKey can generate a one-time credential. Use metadata-only mode for security keys; enable NDEF only for a tag whose contents you intend to read.") },
            confirmButton = { TextButton(onClick = { confirmNdef = false; vm.start(activity, readNdef = true) }) { Text("Start NDEF reader") } },
            dismissButton = { TextButton(onClick = { confirmNdef = false }) { Text("Cancel") } })
        Text(state.status)
        state.error?.let { DiagnosticNotice("NFC", it) }
        Text("Reader sessions stop after 60 seconds or when the app leaves the foreground. Android does not grant access to protected secure-element credentials.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = vm::clear, enabled = !state.reading) { Text("Clear NFC history") }
    }
    item {
        Text("Detected tags", style = MaterialTheme.typography.titleLarge)
        if (state.tags.isEmpty()) Text("No physical tag has been read in this session.")
    }
    itemsIndexed(state.tags, key = { index, tag -> "$index-${tag.observedAtMillis}-${tag.idHex}" }) { _, tag ->
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(DateFormat.getDateTimeInstance().format(Date(tag.observedAtMillis)), style = MaterialTheme.typography.titleMedium)
                Text("Tag ID: ${tag.idHex ?: "Unavailable"} · technologies: ${tag.technologies.joinToString().ifEmpty { "Unavailable" }}")
                if (tag.ndefStatus.contains("failed", ignoreCase = true)) DiagnosticNotice("NFC", tag.ndefStatus)
                else Text("NDEF: ${tag.ndefStatus}")
                if (tag.ndefType != null) Text("NDEF type: ${tag.ndefType} · maximum ${tag.ndefMaxBytes ?: "unknown"} bytes · writable ${tag.ndefWritable ?: "unknown"}")
                tag.ndefRecords.forEachIndexed { index, record ->
                    Text("Record ${index + 1}: TNF ${record.tnf}, type ${record.typeHex}, payload ${record.payloadBytes} bytes")
                    Text(record.payloadHex, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (tag.isoDep) "ISO-DEP supported · max transceive ${tag.isoDepMaxTransceiveBytes ?: "unknown"} bytes · extended APDU ${tag.isoDepExtendedApdu ?: "unknown"}"
                    else "ISO-DEP unavailable")
                tag.isoDepHistoricalHex?.let { Text("Historical bytes: $it", style = MaterialTheme.typography.bodySmall) }
                tag.isoDepHighLayerHex?.let { Text("Higher-layer response: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    item { ApduControls(vm, state) }
}

@Composable
private fun ApduControls(vm: NfcViewModel, state: NfcUiState) {
    var command by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    Text("ISO-DEP command", style = MaterialTheme.typography.titleLarge)
    Text("Only send APDUs to an authorized tag. A command can change tag state. Atlas provides no payment, credential, or write presets and does not save APDU payloads to history.")
    OutlinedTextField(value = command, onValueChange = { command = it.take(700) }, label = { Text("APDU bytes in hex") },
        singleLine = false, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { confirm = true }, enabled = state.reading && state.tags.firstOrNull()?.isoDep == true && !state.apduBusy) {
        Text(if (state.apduBusy) "Waiting for tag" else "Review and send APDU")
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Send this APDU?") },
        text = { Text("Command: ${command.take(522)}\n\nSend only to a tag you are authorized to use. The tag may reject or act on this command.") },
        confirmButton = { TextButton(enabled = state.reading && !state.apduBusy, onClick = { confirm = false; vm.sendApdu(command) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    state.exchanges.take(10).forEach { exchange ->
        Text("${DateFormat.getTimeInstance().format(Date(exchange.atMillis))} · command ${exchange.commandHex} · response ${exchange.responseHex} · SW ${exchange.statusWord ?: "unavailable"}",
            style = MaterialTheme.typography.bodySmall)
    }
}
