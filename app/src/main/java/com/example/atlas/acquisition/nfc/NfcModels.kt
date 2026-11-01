package com.example.atlas.acquisition.nfc

data class NdefRecordReport(val tnf: Int, val typeHex: String, val payloadHex: String, val payloadBytes: Int)

data class NfcTagReport(
    val observedAtMillis: Long,
    val idHex: String?,
    val technologies: List<String>,
    val ndefType: String?,
    val ndefMaxBytes: Int?,
    val ndefWritable: Boolean?,
    val ndefRecords: List<NdefRecordReport>,
    val ndefStatus: String,
    val isoDep: Boolean,
    val isoDepMaxTransceiveBytes: Int?,
    val isoDepExtendedApdu: Boolean?,
    val isoDepHistoricalHex: String?,
    val isoDepHighLayerHex: String?
)

data class ApduExchange(val atMillis: Long, val commandHex: String, val responseHex: String, val statusWord: String?)

data class NfcUiState(
    val reading: Boolean = false,
    val status: String = "Idle — reader mode off",
    val error: String? = null,
    val tags: List<NfcTagReport> = emptyList(),
    val exchanges: List<ApduExchange> = emptyList(),
    val apduBusy: Boolean = false
)

/** Only user-entered, bounded ISO-DEP APDUs are accepted. No payment or credential presets. */
object ApduInput {
    fun parse(hex: String): ByteArray {
        val compact = hex.filterNot(Char::isWhitespace)
        require(compact.length in 8..522 && compact.length % 2 == 0 && compact.all { it.digitToIntOrNull(16) != null }) {
            "Enter 4–261 APDU bytes as hexadecimal pairs."
        }
        return ByteArray(compact.length / 2) { i -> compact.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
