package com.example.atlas

import com.example.atlas.acquisition.wifi.WifiValues
import org.junit.Assert.*
import org.junit.Test

/** SSID octet decoding (API 33+ WifiSsid.getBytes()) and the WifiInfo/legacy text paths; pure JVM. */
class WifiSsidTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun utf8NameDecodesWithoutAddedQuotes() {
        assertEquals("HomeNet", WifiValues.ssidFromBytes("HomeNet".toByteArray(Charsets.UTF_8)))
        assertEquals("HomeNet", WifiValues.ssidFromPlatformText("\"HomeNet\""))
    }

    @Test fun quotesInsideTheNameArePreserved() {
        listOf("My \"Guest\" Net", "\"quoted\"", "\"", "it's").forEach { name ->
            assertEquals(name, WifiValues.ssidFromBytes(name.toByteArray(Charsets.UTF_8)))
            assertEquals(name, WifiValues.ssidFromPlatformText("\"$name\""))
        }
    }

    @Test fun emojiAndMultibyteNamesDecode() {
        listOf("Café ☕", "東京フリーWi-Fi", "📶 Atlas 🛰️", "Ünïcödé").forEach { name ->
            assertEquals(name, WifiValues.ssidFromBytes(name.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test fun emptySsidIsHidden() {
        assertNull(WifiValues.ssidFromBytes(ByteArray(0)))
        assertNull(WifiValues.ssidFromBytes(null))
        assertNull(WifiValues.ssidFromPlatformText("\"\""))
        assertNull(WifiValues.ssidFromPlatformText("<unknown ssid>"))
        assertNull(WifiValues.ssidFromPlatformText(null))
        assertNull(WifiValues.legacySsid(""))
    }

    @Test fun allZeroOctetsAreHidden() {
        assertNull(WifiValues.ssidFromBytes(ByteArray(1)))
        assertNull(WifiValues.ssidFromBytes(ByteArray(7)))
        assertNull(WifiValues.ssidFromBytes(ByteArray(32)))
        assertNull(WifiValues.ssidFromPlatformText("0000000000"))
    }

    @Test fun invalidUtf8IsMarkedHexNotPassedOffAsAName() {
        val prefix = WifiValues.NON_UTF8_SSID_PREFIX
        assertEquals("${prefix}C328>", WifiValues.ssidFromBytes(bytes(0xC3, 0x28)))
        assertEquals("${prefix}FFFE41>", WifiValues.ssidFromBytes(bytes(0xFF, 0xFE, 0x41)))
        assertEquals("${prefix}80>", WifiValues.ssidFromBytes(bytes(0x80)))
        assertEquals("${prefix}436166E9>", WifiValues.ssidFromBytes(bytes(0x43, 0x61, 0x66, 0xE9))) // ISO-8859-1 "Café"
        val truncated = "Café".toByteArray(Charsets.UTF_8).copyOf(4) // cut inside the two-byte é
        assertTrue(WifiValues.ssidFromBytes(truncated)!!.startsWith(prefix))
        // WifiInfo.getSSID() reports the same octets as unquoted hex digits; both paths agree.
        assertEquals(WifiValues.ssidFromBytes(bytes(0xC3, 0x28)), WifiValues.ssidFromPlatformText("c328"))
    }

    @Test fun maximumThirtyTwoOctetSsidIsKeptWhole() {
        val ascii = "A".repeat(32)
        assertEquals(ascii, WifiValues.ssidFromBytes(ascii.toByteArray(Charsets.UTF_8)))
        val emoji = "📶".repeat(8) // 8 x 4 octets
        assertEquals(32, emoji.toByteArray(Charsets.UTF_8).size)
        assertEquals(emoji, WifiValues.ssidFromBytes(emoji.toByteArray(Charsets.UTF_8)))
        val invalid = ByteArray(32) { 0xFF.toByte() }
        assertEquals(WifiValues.NON_UTF8_SSID_PREFIX + "FF".repeat(32) + ">", WifiValues.ssidFromBytes(invalid))
    }

    @Test fun controlCharactersAndZeroPaddingFollowExistingSanitizing() {
        assertEquals("Lab", WifiValues.ssidFromBytes(bytes(0x4C, 0x61, 0x62, 0x00, 0x00)))
        assertEquals("test", WifiValues.ssidFromBytes("te\u202Est\n".toByteArray(Charsets.UTF_8)))
    }

    @Test fun legacyApiTextKeepsQuoteStripping() {
        assertEquals("Home", WifiValues.legacySsid("\"Home\""))
        assertEquals("Home", WifiValues.legacySsid("Home"))
        assertEquals("cafe", WifiValues.legacySsid("cafe")) // legacy text is never hex-decoded
        assertEquals("cafe", WifiValues.ssidFromPlatformText("\"cafe\""))
    }
}
