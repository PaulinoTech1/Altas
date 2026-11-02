package com.example.atlas.acquisition.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import com.example.atlas.acquisition.bluetooth.hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidNfcReader(context: Context) {
    private val adapter = context.applicationContext.getSystemService(NfcManager::class.java)?.defaultAdapter
    @Volatile private var activeIso: IsoDep? = null
    @Volatile private var activeNdef: Ndef? = null

    fun start(activity: Activity, readNdef: Boolean, onTag: (Tag) -> Unit) {
        val nfc = checkNotNull(adapter) { "NFC hardware is unavailable." }
        check(nfc.isEnabled) { "NFC is disabled. Enable it in Android settings before reading." }
        val technologies = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE
        // Security keys can emit one-time credentials as NDEF. Metadata-only is the default.
        val flags = technologies or if (readNdef) 0 else NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfc.enableReaderMode(activity, onTag, flags, null)
    }

    fun stop(activity: Activity) {
        try { adapter?.disableReaderMode(activity) } catch (_: IllegalStateException) { /* Activity is no longer foreground. */ }
        catch (_: UnsupportedOperationException) { /* Adapter unavailable. */ }
        closeIo()
    }

    fun closeIo() {
        val iso = activeIso
        activeIso = null
        try { iso?.close() } catch (_: Exception) { /* A tag can leave the field during cleanup. */ }
        val ndef = activeNdef
        activeNdef = null
        try { ndef?.close() } catch (_: Exception) { /* A tag can leave the field during cleanup. */ }
    }

    suspend fun inspect(tag: Tag, readNdef: Boolean): NfcTagReport = withContext(Dispatchers.IO) {
        val observedAt = System.currentTimeMillis()
        val ndef = if (readNdef) Ndef.get(tag) else null
        val iso = IsoDep.get(tag)
        var records = emptyList<NdefRecordReport>()
        var ndefStatus = if (!readNdef) "NDEF deliberately skipped in metadata-only mode."
            else if (ndef == null) "NDEF not supported on this tag." else "No NDEF message."
        if (ndef != null) {
            try {
                currentCoroutineContext().ensureActive()
                activeNdef = ndef
                ndef.connect()
                val message = ndef.ndefMessage
                if (message != null) {
                    records = message.records.take(32).map { record ->
                        val payload = record.payload
                        NdefRecordReport(record.tnf.toInt(), record.type.take(64).toByteArray().hex(),
                            payload.take(512).toByteArray().hex(), payload.size)
                    }
                    ndefStatus = "${message.records.size} NDEF records reported; up to 32 displayed, payloads capped at 512 bytes each."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ndefStatus = "NDEF read failed or the tag left the field." }
            finally {
                activeNdef = null
                try { ndef.close() } catch (_: Exception) { /* Tag already gone. */ }
            }
        }
        NfcTagReport(
            observedAt, tag.id?.take(16)?.toByteArray()?.hex(), tag.techList.take(32).map { it.substringAfterLast('.') },
            ndef?.type, ndef?.maxSize, ndef?.isWritable, records, ndefStatus,
            iso != null, iso?.maxTransceiveLength, iso?.isExtendedLengthApduSupported,
            iso?.historicalBytes?.take(64)?.toByteArray()?.hex(), iso?.hiLayerResponse?.take(64)?.toByteArray()?.hex()
        )
    }

    suspend fun exchange(tag: Tag, command: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val iso = checkNotNull(IsoDep.get(tag)) { "The last tag does not support ISO-DEP." }
        currentCoroutineContext().ensureActive()
        activeIso = iso
        try {
            iso.connect()
            iso.timeout = 5_000
            check(command.size <= iso.maxTransceiveLength) { "APDU exceeds this tag's transceive limit." }
            val response = iso.transceive(command)
            check(response.size <= 4096) { "ISO-DEP response is too large to display safely." }
            response
        } finally {
            activeIso = null
            try { iso.close() } catch (_: Exception) { /* Tag already gone. */ }
        }
    }
}
