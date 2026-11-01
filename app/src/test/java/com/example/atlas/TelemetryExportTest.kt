package com.example.atlas

import com.example.atlas.telemetry.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.StringWriter

/** Staged export (H2): snapshot under the lock into a private file, copy to the document afterwards. Pure JVM. */
class TelemetryExportTest {
    private fun rows(n: Int) = (1..n).map {
        Measurement("dedupe-$it", eventId = "event-$it", radioType = "WIFI", eventTimestampMillis = it.toLong(),
            interfaceId = "11:22:33:44:55:66", measurementType = "RSSI", value = -60.0, units = "dBm",
            collectionStatus = "MEASURED", permissionState = "GRANTED_AT_DELIVERY", sourceApi = "test",
            freshness = "CACHED", metadataJson = """{"ssid":"name \"quoted\""}""")
    }
    private fun staging() = File.createTempFile("atlas-export-test-", ".json")
    private fun pagedSnapshot(rows: List<Measurement>, redact: Boolean = false): suspend (java.io.Writer) -> Int = { writer ->
        TelemetryJson.writePaged(writer, rows.size, redact, 200) { offset -> rows.drop(offset).take(200) }
        rows.size
    }

    @Test fun stagedExportIsByteIdenticalToDirectPagedWrite() = runBlocking {
        val rows = rows(405)
        val direct = StringWriter().also { TelemetryJson.writePaged(it, rows.size, false, 200) { offset -> rows.drop(offset).take(200) } }
        val output = ByteArrayOutputStream()
        val file = staging()
        assertEquals(405, TelemetryExport.stageThenCopy(file, pagedSnapshot(rows)) { output })
        assertEquals(direct.toString(), output.toString(Charsets.UTF_8.name()))
        val doc = Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject
        assertEquals(405, doc.getValue("recordCount").jsonPrimitive.int)
        assertEquals(405, doc.getValue("measurements").jsonArray.size)
        assertFalse("staging file must be deleted", file.exists())
    }

    @Test fun snapshotFailureNeverOpensTheDocument() {
        var opened = false
        val file = staging()
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { TelemetryExport.stageThenCopy(file, { error("Export snapshot changed unexpectedly.") }) { opened = true; ByteArrayOutputStream() } }
        }
        assertEquals("Export snapshot changed unexpectedly.", failure.message)
        assertFalse(opened)
        assertFalse(file.exists())
    }

    @Test fun documentIsWrittenOnlyAfterTheSnapshotLockIsReleased() = runBlocking {
        val lock = Mutex() // stands in for the repository mutex and Room transaction
        var savedDuringCopy = false
        val slowProvider = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                assertFalse("lock must not be held while streaming to the provider", lock.isLocked)
                // A concurrent save can take the lock while the provider is still being written.
                if (!savedDuringCopy) savedDuringCopy = runBlocking { lock.withLock { true } }
            }
        }
        val count = TelemetryExport.stageThenCopy(staging(), { writer -> lock.withLock { pagedSnapshot(rows(3))(writer) } }) { slowProvider }
        assertEquals(3, count)
        assertTrue(savedDuringCopy)
    }

    @Test fun providerFailureIsReportedAsIncompleteAndPartialOutputIsNotValidJson() {
        val partial = ByteArrayOutputStream()
        val failing = object : OutputStream() {
            var budget = 100
            override fun write(b: Int) {
                if (budget-- <= 0) throw IOException("Simulated provider disconnect")
                partial.write(b)
            }
        }
        val file = staging()
        val failure = assertThrows(TelemetryExport.DestinationException::class.java) {
            runBlocking { TelemetryExport.stageThenCopy(file, pagedSnapshot(rows(50))) { failing } }
        }
        assertTrue(failure.cause is IOException)
        assertTrue(failure.message!!.contains("incomplete"))
        assertEquals(100, partial.size())
        val text = partial.toString(Charsets.UTF_8.name())
        assertThrows(Exception::class.java) { Json.parseToJsonElement(text) } // truncated output never parses as a complete export
        assertFalse(file.exists())
    }

    @Test fun providerThatCannotOpenIsReportedAndStagingRemoved() {
        val file = staging()
        val failure = assertThrows(TelemetryExport.DestinationException::class.java) {
            runBlocking { TelemetryExport.stageThenCopy(file, pagedSnapshot(rows(1))) { error("Document provider did not open a stream.") } }
        }
        assertTrue(failure.cause is IllegalStateException)
        assertFalse(file.exists())
    }

    @Test fun providerFailureOnCloseIsNotReportedAsSuccess() {
        val failing = object : OutputStream() {
            override fun write(b: Int) { }
            override fun close() { throw IOException("Simulated upload failure on close") }
        }
        assertThrows(TelemetryExport.DestinationException::class.java) {
            runBlocking { TelemetryExport.stageThenCopy(staging(), pagedSnapshot(rows(2))) { failing } }
        }
    }
}
