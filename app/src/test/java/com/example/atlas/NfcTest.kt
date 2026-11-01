package com.example.atlas

import com.example.atlas.acquisition.nfc.ApduInput
import com.example.atlas.acquisition.nfc.NfcTagReport
import com.example.atlas.telemetry.TelemetryJson
import com.example.atlas.telemetry.TelemetryNormalizer
import org.junit.Assert.*
import org.junit.Test

class NfcTest {
    @Test fun apduHexMustBeBoundedAndWellFormed() {
        assertArrayEquals(byteArrayOf(0, -92, 4, 0), ApduInput.parse("00 A4 04 00"))
        listOf("", "00A4040", "00A4040Z", "00A4", "00".repeat(262)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ApduInput.parse(invalid) }
        }
    }

    @Test fun nfcDiscoveryStoresNoNdefOrApduPayloadAndRedactsTagId() {
        val tag = NfcTagReport(1234, "DEADBEEF", listOf("NfcA", "Ndef"), "org.nfcforum.ndef.type2", 1024, false,
            emptyList(), "NDEF read", false, null, null, null, null)
        val row = TelemetryNormalizer.nfc(tag, "process-clock").single()
        assertEquals("NFC", row.radioType)
        assertEquals("MEASURED", row.collectionStatus)
        assertEquals(1.0, row.value!!, 0.0)
        assertEquals("DEADBEEF", row.interfaceId)
        assertFalse(row.metadataJson.contains("payloadHex"))
        val redacted = TelemetryJson.measurement(row, true).toString()
        assertFalse(redacted.contains("DEADBEEF"))
        assertTrue(redacted.contains("\"metadata\":{}"))
    }
}
