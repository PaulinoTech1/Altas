package com.example.atlas.telemetry

import kotlinx.serialization.json.*
import java.io.Writer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object TelemetryJson {
    const val SCHEMA_VERSION = 1
    private val json = Json { prettyPrint = false }

    fun measurement(row: Measurement, redact: Boolean): JsonObject = buildJsonObject {
        put("eventId", row.eventId)
        put("radioType", row.radioType)
        put("eventTimestampMillis", row.eventTimestampMillis)
        put("sourceTimestampMillis", row.sourceTimestampMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceTimeBasis", row.sourceTimeBasis)
        put("receivedElapsedMillis", row.receivedElapsedMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("bootId", row.bootId?.let(::JsonPrimitive) ?: JsonNull)
        put("interfaceId", if (redact) JsonNull else row.interfaceId?.let(::JsonPrimitive) ?: JsonNull)
        put("measurementType", row.measurementType)
        put("value", row.value?.let(::JsonPrimitive) ?: JsonNull)
        put("units", row.units?.let(::JsonPrimitive) ?: JsonNull)
        put("collectionStatus", row.collectionStatus)
        put("permissionState", row.permissionState)
        put("sourceApi", row.sourceApi)
        put("freshnessAtReceipt", row.freshness)
        // All metadata is removed together: identifiers may occur inside advertisements/cell metadata.
        put("metadata", if (redact) buildJsonObject {} else json.parseToJsonElement(row.metadataJson))
    }

    /** Streams each record; database snapshot is bounded at 50,000 records. Caller owns the writer. */
    fun write(writer: Writer, rows: List<Measurement>, redact: Boolean, exportedAtMillis: Long) {
        header(writer, rows.size, redact, exportedAtMillis)
        rows.forEachIndexed { index, row ->
            if (index > 0) writer.write(",")
            writer.write(measurement(row, redact).toString())
        }
        writer.write("]}")
        writer.flush()
    }

    private fun header(writer: Writer, count: Int, redact: Boolean, at: Long) {
        writer.write("{\"schemaVersion\":$SCHEMA_VERSION,\"exportedAtMillis\":$at,\"identifiersRedacted\":$redact,\"recordCount\":$count,\"measurements\":[")
    }

    suspend fun writePaged(writer: Writer, count: Int, redact: Boolean, at: Long, page: suspend (Int) -> List<Measurement>) {
        header(writer, count, redact, at)
        var written = 0
        while (written < count) {
            currentCoroutineContext().ensureActive()
            val rows = page(written)
            check(rows.isNotEmpty() && written + rows.size <= count) { "Export snapshot changed unexpectedly." }
            rows.forEach {
                if (written > 0) writer.write(",")
                writer.write(measurement(it, redact).toString())
                written++
            }
        }
        writer.write("]}"); writer.flush()
    }
}
