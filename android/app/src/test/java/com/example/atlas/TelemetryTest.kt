package com.example.atlas

import com.example.atlas.telemetry.*
import com.example.atlas.acquisition.cellular.*
import com.example.atlas.acquisition.wifi.*
import com.example.atlas.acquisition.bluetooth.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.io.Writer
import java.io.IOException
import kotlinx.coroutines.runBlocking

class TelemetryTest {
    private fun sensitiveRow() = Measurement("private-dedupe", radioType = "WIFI", eventTimestampMillis = 100,
        interfaceId = "11:22:33:44:55:66", measurementType = "RSSI", value = null, units = "dBm",
        collectionStatus = "UNAVAILABLE", permissionState = "GRANTED_AT_DELIVERY", sourceApi = "test",
        freshness = "UNKNOWN", metadataJson = """{"ssid":"private-ssid","nested":{"payload":"secret"}}""")

    @Test fun redactedExportRemovesIdentifiersAndNestedMetadata() {
        val writer = StringWriter()
        TelemetryJson.write(writer, listOf(sensitiveRow()), true, 200)
        val text = writer.toString()
        assertFalse(text.contains("11:22")); assertFalse(text.contains("private-ssid"))
        assertFalse(text.contains("secret")); assertFalse(text.contains("private-dedupe"))
        val document = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.int)
        val record = document.getValue("measurements").jsonArray.single().jsonObject
        assertEquals(JsonNull, record.getValue("value"))
        assertEquals(JsonNull, record.getValue("interfaceId"))
        assertEquals("UNAVAILABLE", record.getValue("collectionStatus").jsonPrimitive.content)
    }

    @Test fun fullExportPreservesMetadataAndEscapesUntrustedText() {
        val row = sensitiveRow().copy(metadataJson = """{"name":"quoted\"\nname"}""")
        val writer = StringWriter()
        TelemetryJson.write(writer, listOf(row), false, 200)
        val exported = Json.parseToJsonElement(writer.toString()).jsonObject.getValue("measurements").jsonArray.single().jsonObject
        assertEquals(row.interfaceId, exported.getValue("interfaceId").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(row.metadataJson), exported.getValue("metadata"))
    }

    @Test fun emptyExportIsValidAndCounted() {
        val writer = StringWriter(); TelemetryJson.write(writer, emptyList(), true, 200)
        val doc = Json.parseToJsonElement(writer.toString()).jsonObject
        assertTrue(doc.getValue("measurements").jsonArray.isEmpty())
        assertEquals(0, doc.getValue("recordCount").jsonPrimitive.int)
    }

    @Test fun zeroSinrIsPreservedAndUnavailableIsNull() {
        val signal = SignalReport(listOf(SubscriptionSignal("NR", CellularValues.nr(-100, -15, 0))), 1000, 500, 400, 1)
        val rows = TelemetryNormalizer.signal(signal, "clock")
        assertEquals(0.0, rows.single { it.measurementType == "NR SS-SINR" }.value!!, 0.0)
        val missing = signal.copy(signals = listOf(SubscriptionSignal("NR", CellularValues.nr(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))))
        assertTrue(TelemetryNormalizer.signal(missing, "clock").all { it.value == null && it.collectionStatus == "UNAVAILABLE" })
    }

    @Test fun repeatedSignalSaveHasStableKeysAndDifferentOriginsRemainDistinct() {
        val report = SignalReport(listOf(SubscriptionSignal("NR", CellularValues.nr(-100, -15, 1))), 1000, 500, 400)
        val first = TelemetryNormalizer.signal(report, "clock")
        assertEquals(first.map { it.dedupeKey }, TelemetryNormalizer.signal(report, "clock").map { it.dedupeKey })
        assertNotEquals(first.first().dedupeKey, TelemetryNormalizer.signal(report, "other-clock").first().dedupeKey)
        assertEquals("ELAPSED_REALTIME_MILLIS", first.first().sourceTimeBasis)
    }

    @Test fun cachedCellMeasurementIsNotLabeledFresh() {
        val cell = CellRecord("NR", true, 1, null, 0, null, null, null, null, CellularValues.nr(-100, -15, 0), 400)
        val batch = CellBatch("batch", listOf(cell), 1, null, CellDelivery.DEVICE_CACHE, 1000, 500, 450, "cache")
        val rows = TelemetryNormalizer.cells(batch, "clock")
        assertTrue(rows.all { it.collectionStatus == "CACHED" && it.interfaceId == null })
        assertTrue(rows.all { it.sourceTimestampMillis == 400L })
    }

    @Test fun wifiCacheDuplicateHasStableKeyAcrossDeliveryIds() {
        val row = WifiObservation("one", "test", "aa:bb:cc:dd:ee:ff", -70, 2412, 1, "20 MHz", null, null, "WPA2", 1000, 500000, 400000, 450000, WifiDelivery.CACHE_READ)
        val first = TelemetryNormalizer.wifi(row, "clock")
        assertEquals(first.map { it.dedupeKey }, TelemetryNormalizer.wifi(row.copy(eventId = "two", observedAtMillis = 2000), "clock").map { it.dedupeKey })
        assertTrue(first.all { it.collectionStatus == "CACHED" })
    }

    @Test fun bleTimestampUnitsAndAdvertisementRedactionAreCorrect() {
        val row = BleObservation("event", "aa:bb:cc:dd:ee:ff", 1000, 500000000, 400000000, -70, true,
            Advertisement(name = "secret-device", rawHex = "CAFEBABE"))
        val normalized = TelemetryNormalizer.ble(row, "clock")
        assertEquals(400L, normalized.sourceTimestampMillis)
        assertEquals(500L, normalized.receivedElapsedMillis)
        val redacted = TelemetryJson.measurement(normalized, true).toString()
        assertFalse(redacted.contains("secret-device")); assertFalse(redacted.contains("CAFEBABE"))
    }

    @Test fun diagnosticsNeverProduceFakeSignalValues() {
        listOf("PERMISSION_DENIED", "UNSUPPORTED", "FAILED", "UNAVAILABLE").forEach { status ->
            val row = TelemetryNormalizer.diagnostic("CELLULAR", status, "DENIED", 1000, "test", "unavailable")
            assertNull(row.value); assertNull(row.units); assertEquals(status, row.collectionStatus)
        }
    }

    @Test fun invalidRetentionFailsRatherThanDisablingBounds() {
        assertThrows(IllegalArgumentException::class.java) { StoragePreferences(0, 10000) }
        assertThrows(IllegalArgumentException::class.java) { StoragePreferences(7, Int.MAX_VALUE) }
    }

    @Test fun pagedExportHasMatchingCountAndValidSeparators() = runBlocking {
        val rows = (1..405).map { sensitiveRow().copy(eventId = "event-$it") }
        val offsets = mutableListOf<Int>()
        val writer = StringWriter()
        TelemetryJson.writePaged(writer, rows.size, true, 200) { offset -> offsets.add(offset); rows.drop(offset).take(200) }
        val doc = Json.parseToJsonElement(writer.toString()).jsonObject
        assertEquals(listOf(0, 200, 400), offsets)
        assertEquals(405, doc.getValue("measurements").jsonArray.size)
        assertEquals(405, doc.getValue("recordCount").jsonPrimitive.int)
    }

    @Test fun documentWriteFailurePropagates() {
        val writer = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) { throw IOException("Simulated unavailable provider") }
            override fun flush() { }
            override fun close() { }
        }
        assertThrows(IOException::class.java) { runBlocking { TelemetryJson.writePaged(writer, 1, true, 200) { listOf(sensitiveRow()) } } }
    }
}
