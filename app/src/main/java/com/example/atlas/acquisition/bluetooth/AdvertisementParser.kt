package com.example.atlas.acquisition.bluetooth

import java.nio.ByteBuffer
import java.util.UUID

/** Bluetooth AD length includes type but excludes length octet. Never reads past a field. */
object AdvertisementParser {
    const val MAX_BYTES = 1650

    fun parse(input: ByteArray?, sourceTruncated: Boolean = false): Advertisement {
        if (input == null) return Advertisement(truncated = sourceTruncated)
        val bytes = input.take(MAX_BYTES).toByteArray()
        var offset = 0
        var name: String? = null
        var completeName = false
        var tx: Int? = null
        var malformed = false
        val uuids = linkedSetOf<String>()
        val manufacturers = mutableListOf<ManufacturerData>()
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 0xff
            if (length == 0) break
            val end = offset + length + 1
            if (end > bytes.size || length < 1) { malformed = true; break }
            val type = bytes[offset + 1].toInt() and 0xff
            val payload = bytes.copyOfRange(offset + 2, end)
            when (type) {
                0x08, 0x09 -> if (!completeName) {
                    name = payload.toString(Charsets.UTF_8).filterNot { it.isISOControl() }.take(248)
                    completeName = type == 0x09
                }
                0x02, 0x03, 0x04, 0x05, 0x06, 0x07 -> {
                    val width = when (type) { 0x02, 0x03 -> 2; 0x04, 0x05 -> 4; else -> 16 }
                    if (payload.size % width != 0) malformed = true
                    for (start in 0 until (payload.size / width) * width step width) {
                        val field = payload.copyOfRange(start, start + width)
                        uuids += if (width == 16) {
                            val buffer = ByteBuffer.wrap(field.reversedArray())
                            UUID(buffer.long, buffer.long).toString()
                        } else {
                            val value = field.indices.fold(0L) { value, index ->
                                value or ((field[index].toLong() and 0xff) shl (8 * index))
                            }
                            "%08x-0000-1000-8000-00805f9b34fb".format(value)
                        }
                    }
                }
                0x0A -> if (payload.size == 1) tx = payload[0].toInt() else malformed = true
                0xFF -> if (payload.size >= 2) {
                    val id = (payload[0].toInt() and 0xff) or ((payload[1].toInt() and 0xff) shl 8)
                    manufacturers += ManufacturerData(id, payload.copyOfRange(2, payload.size).hex())
                } else malformed = true
            }
            offset = end
        }
        return Advertisement(name, uuids.toList(), manufacturers, tx, bytes.hex(), malformed,
            sourceTruncated || input.size > MAX_BYTES)
    }
}
