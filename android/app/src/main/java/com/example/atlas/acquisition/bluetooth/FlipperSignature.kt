package com.example.atlas.acquisition.bluetooth

/** Advertised identifiers are self-reported and spoofable. This is a hint, never device authentication. */
object FlipperSignature {
    const val SERIAL_SERVICE = "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000"

    data class Match(val label: String, val evidence: List<String>)

    fun assess(device: BleDeviceHistory): Match? {
        val ad = device.latest.advertisement
        val name = ad.name?.trim().orEmpty()
        val nameHint = name.equals("Flipper", true) || name.startsWith("Flipper ", true) ||
            name.startsWith("Flipper-", true) || name.startsWith("Flipper_", true)
        val serviceHint = ad.serviceUuids.any { it.equals(SERIAL_SERVICE, true) }
        if (!nameHint && !serviceHint) return null
        val evidence = buildList {
            if (nameHint) add("Advertised name resembles Flipper")
            if (serviceHint) add("Advertises Flipper firmware BLE serial service")
            if (device.receivedCount >= 3) add("Seen ${device.receivedCount} times at this address")
        }
        val label = if (nameHint && serviceHint && device.receivedCount >= 3) "Heightened Flipper Zero possibility"
            else "Possible Flipper Zero signal"
        return Match(label, evidence)
    }
}
