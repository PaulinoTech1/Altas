package com.example.atlas

import com.example.atlas.ui.DiagnosticCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCatalogTest {
    @Test fun platformStatusIsPreservedWithoutGuessingItsMeaning() {
        val scan = DiagnosticCatalog.describe("BLE", "BLE scan failed: 6 (scanning too frequently). No automatic retry.")
        assertEquals("ATLAS-BLE-OS-6", scan.code)
        val gatt = DiagnosticCatalog.describe("GATT", "Read failed (GATT status 5). Authorization may be required.")
        assertEquals("ATLAS-GATT-OS-5", gatt.code)
        assertTrue(gatt.nextStep.contains("does not identify a unique cause"))
    }

    @Test fun staleMeasurementsAreWarningsAndUnknownFailuresHaveStableFallbacks() {
        val cached = DiagnosticCatalog.describe("WIFI", "Android reported no updated results. Displayed rows may be cached.")
        assertEquals("ATLAS-WIFI-CACHED", cached.code)
        assertTrue(cached.warning)
        assertEquals("ATLAS-CELL-OPERATION", DiagnosticCatalog.describe("CELL", "unexpected failure").code)
        assertEquals("ATLAS-NFC-APDU-INPUT", DiagnosticCatalog.describe("NFC", "Invalid APDU hex length.").code)
        assertEquals("ATLAS-WIFI-LOCAL-RATE", DiagnosticCatalog.describe("WIFI", "LOCAL_RATE_LIMIT: wait 30 seconds").code)
        assertEquals("ATLAS-CELL-UPDATE", DiagnosticCatalog.describe("CELL", "MODEM_ERROR: request failed").code)
        assertEquals("ATLAS-CELL-OS-2", DiagnosticCatalog.describe("CELL", "MODEM_ERROR: Cell update failed (platform code 2)").code)
        assertEquals("ATLAS-STORAGE-SAVE", DiagnosticCatalog.describe("STORAGE", "Save failed (IOException).").code)
    }
}
