package com.example.atlas.telemetry

import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.acquisition.wifi.*
import com.example.atlas.acquisition.cellular.*
import com.example.atlas.acquisition.nfc.NfcTagReport
import kotlinx.serialization.json.*
import java.security.MessageDigest

object TelemetryNormalizer {
    fun key(vararg parts: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(JsonArray(parts.map { it?.toString()?.let(::JsonPrimitive) ?: JsonNull }).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun row(key: String, radio: String, at: Long, received: Long?, source: Long?, boot: String,
        id: String?, type: String, value: Int?, unit: String, api: String, freshness: String, metadata: JsonObject) = Measurement(
        dedupeKey = key, radioType = radio, eventTimestampMillis = at,
        sourceTimestampMillis = source, sourceTimeBasis = if (source == null) "UNAVAILABLE" else "ELAPSED_REALTIME_MILLIS",
        receivedElapsedMillis = received, bootId = boot, interfaceId = id,
        measurementType = type, value = value?.toDouble(), units = unit,
        collectionStatus = when {
            value == null -> "UNAVAILABLE"
            freshness.contains("cached", ignoreCase = true) || freshness.contains("stale", ignoreCase = true) -> "CACHED"
            else -> "MEASURED"
        },
        permissionState = "GRANTED_AT_DELIVERY", sourceApi = api, freshness = freshness, metadataJson = metadata.toString())

    fun ble(value: BleObservation, boot: String): Measurement = row(key(boot, value.eventId, "RSSI"), "BLUETOOTH",
        value.observedAtMillis, value.receivedElapsedNanos / 1_000_000, value.sourceElapsedNanos?.div(1_000_000), boot,
        value.address, "BLE RSSI", value.rssiDbm, "dBm", value.sourceApi,
        when { value.ageMillis(value.receivedElapsedNanos) == null -> "UNKNOWN"; value.ageMillis(value.receivedElapsedNanos)!! > 30000 -> "STALE"; else -> "RECENT_PLATFORM_REPORT" },
        buildJsonObject {
            put("name", value.advertisement.name?.let(::JsonPrimitive) ?: JsonNull)
            put("connectable", value.connectable)
            put("serviceUuids", JsonArray(value.advertisement.serviceUuids.map(::JsonPrimitive)))
            put("manufacturerData", JsonArray(value.advertisement.manufacturerData.map { buildJsonObject {
                put("companyId", it.companyId); put("payloadHex", it.payloadHex)
            } }))
            put("rawAdvertisementHex", value.advertisement.rawHex)
            put("malformed", value.advertisement.malformed); put("truncated", value.advertisement.truncated)
        })

    fun wifi(value: WifiObservation, boot: String): List<Measurement> {
        val meta = buildJsonObject {
            put("ssid", value.ssid?.let(::JsonPrimitive) ?: JsonNull)
            put("channel", value.channel?.let(::JsonPrimitive) ?: JsonNull)
            put("channelWidth", value.channelWidth)
            put("securityCapabilities", value.capabilities?.let(::JsonPrimitive) ?: JsonNull)
            put("delivery", value.delivery.name)
        }
        return listOf("Wi-Fi RSSI" to (value.rssiDbm to "dBm"), "Wi-Fi frequency" to (value.frequencyMhz to "MHz")).map { (type, metric) ->
            row(key(boot, "wifi", value.bssid ?: value.eventId, value.sourceElapsedMicros ?: value.eventId, type), "WIFI",
                value.observedAtMillis, value.receivedElapsedMicros / 1000, value.sourceElapsedMicros?.div(1000), boot,
                value.bssid, type, metric.first, metric.second, value.sourceApi, value.freshness(value.receivedElapsedMicros).name, meta)
        }
    }

    fun connection(value: WifiConnection, boot: String): List<Measurement> = listOf(
        "Connected Wi-Fi RSSI" to (value.rssiDbm to "dBm"),
        "Connected Wi-Fi frequency" to (value.frequencyMhz to "MHz"),
        "Wi-Fi link speed" to (value.linkSpeedMbps to "Mbps")
    ).map { (type, metric) -> row(key(boot, "connection", value.key, value.observedAtMillis, type), "WIFI",
        value.observedAtMillis, null, null, boot, value.bssid, type, metric.first, metric.second,
        "ConnectivityManager / NetworkCapabilities / WifiInfo", "SOURCE_AGE_UNKNOWN", buildJsonObject {
            put("ssid", value.ssid?.let(::JsonPrimitive) ?: JsonNull)
            put("validated", value.validated)
            put("linkSpeedIsNotThroughput", true)
        }) }

    fun cells(batch: CellBatch, boot: String): List<Measurement> = batch.rows.flatMapIndexed { index, cell ->
        cell.metrics.map { metric -> row(key(boot, "cell", batch.subscriptionContext, cell.rat, cell.cellId, cell.pci,
            cell.sourceElapsedMillis ?: batch.eventId, index, metric.name), "CELLULAR", batch.receivedAtMillis,
            batch.receivedElapsedMillis, cell.sourceElapsedMillis, boot, cell.cellId?.toString(), metric.name, metric.value,
            metric.unit, batch.sourceApi, batch.freshness(cell, batch.receivedElapsedMillis), buildJsonObject {
                put("rat", cell.rat); put("registered", cell.registered); put("connectionStatus", cell.connectionStatus)
                put("pci", cell.pci?.let(::JsonPrimitive) ?: JsonNull); put("areaCode", cell.areaCode?.let(::JsonPrimitive) ?: JsonNull)
                put("channel", cell.channel?.let(::JsonPrimitive) ?: JsonNull)
                put("mcc", cell.mcc?.let(::JsonPrimitive) ?: JsonNull); put("mnc", cell.mnc?.let(::JsonPrimitive) ?: JsonNull)
                put("subscriptionContext", batch.subscriptionContext?.let(::JsonPrimitive) ?: JsonNull)
                put("delivery", batch.delivery.name)
            }) }
    }

    fun signal(report: SignalReport, boot: String): List<Measurement> = report.signals.flatMapIndexed { index, signal ->
        signal.metrics.map { metric -> row(key(boot, "signal", report.subscriptionContext, report.receivedElapsedMillis, index, metric.name),
            "CELLULAR", report.receivedAtMillis, report.receivedElapsedMillis, report.sourceElapsedMillis, boot, null,
            metric.name, metric.value, metric.unit, "TelephonyCallback.SignalStrengthsListener / SignalStrength",
            when (val age = CellularValues.age(report.sourceElapsedMillis, report.receivedElapsedMillis)) {
                null -> "UNKNOWN"; in 0..30000 -> "RECENT_PLATFORM_REPORT"; else -> if (age > 30000) "STALE" else "UNKNOWN"
            }, buildJsonObject {
                put("rat", signal.rat); put("subscriptionContext", report.subscriptionContext?.let(::JsonPrimitive) ?: JsonNull)
                put("scope", "Subscription signal; not assigned to a CellInfo row")
            }) }
    }

    fun nfc(tag: NfcTagReport, boot: String): List<Measurement> {
        val metadata = buildJsonObject {
            put("technologies", JsonArray(tag.technologies.map(::JsonPrimitive)))
            put("ndefType", tag.ndefType?.let(::JsonPrimitive) ?: JsonNull)
            put("ndefStatus", tag.ndefStatus)
            put("isoDep", tag.isoDep)
            put("ndefRecordCount", tag.ndefRecords.size)
        }.toString()
        return listOf(
            Measurement(key(boot, "nfc-tag", tag.idHex, tag.observedAtMillis), radioType = "NFC",
                eventTimestampMillis = tag.observedAtMillis, bootId = boot, interfaceId = tag.idHex,
                measurementType = "NFC tag discovery", value = 1.0, units = "event", collectionStatus = "MEASURED",
                permissionState = "GRANTED_AT_DELIVERY", sourceApi = "NfcAdapter.ReaderCallback / Tag", freshness = "SOURCE_AGE_UNKNOWN", metadataJson = metadata)
        )
    }

    fun diagnostic(radio: String, status: String, permission: String, at: Long, api: String, detail: String): Measurement {
        require(status in setOf("PERMISSION_DENIED", "UNSUPPORTED", "FAILED", "UNAVAILABLE", "CACHED"))
        return Measurement(key(radio, status, permission, at, detail), radioType = radio, eventTimestampMillis = at,
            measurementType = "Collection diagnostic", collectionStatus = status, permissionState = permission,
            sourceApi = api, freshness = "NOT_A_MEASUREMENT", metadataJson = buildJsonObject { put("detail", detail) }.toString())
    }
}
