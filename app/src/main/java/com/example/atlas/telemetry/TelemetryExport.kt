package com.example.atlas.telemetry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.Writer

/** Two-phase export: snapshot into a private staging file, then copy to the user's document with no lock held. */
object TelemetryExport {
    /** The chosen document was opened (and truncated) but not completely written; it must not be treated as an export. */
    class DestinationException(cause: Throwable) :
        IOException("Export failed while writing the chosen document; it is incomplete and not a valid export. Delete it or export again", cause)

    /**
     * [snapshot] writes the whole document to [staging] and returns the record count; the caller holds its locks only there.
     * [destination] is opened only after the snapshot completed, so snapshot failures never touch the chosen document.
     * [staging] is always deleted.
     */
    suspend fun stageThenCopy(staging: File, snapshot: suspend (Writer) -> Int, destination: () -> OutputStream): Int {
        try {
            val count = staging.bufferedWriter(Charsets.UTF_8).use { writer -> snapshot(writer) }
            currentCoroutineContext().ensureActive()
            try {
                destination().use { output ->
                    staging.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                    output.flush()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { throw DestinationException(failure) }
            return count
        } finally { staging.delete() }
    }
}
