package com.example.atlas.telemetry

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import com.example.atlas.AtlasApplication

/** One application-scoped database; never enables destructive migration or main-thread queries. */
class TelemetryRepository internal constructor(context: Context) {
    private val app = context.applicationContext
    val database = Room.databaseBuilder(app, TelemetryDatabase::class.java, "atlas-telemetry.db").build()
    val preferences = TelemetryPreferences(app)
    val dao = database.measurements()
    private val mutex = Mutex()
    // Process-scoped origin avoids ever comparing elapsed timestamps from different boots/processes.
    val bootId: String = UUID.randomUUID().toString()

    suspend fun save(rows: List<Measurement>): Int = mutex.withLock {
        val settings = preferences.values.first()
        database.withTransaction {
            val inserted = dao.insert(rows).count { it != -1L }
            prune(settings)
            inserted
        }
    }

    private suspend fun prune(value: StoragePreferences) {
        dao.expire(System.currentTimeMillis() - value.retentionDays * 86_400_000L)
        dao.cap(value.maximumRows)
    }

    suspend fun maintain() = mutex.withLock { database.withTransaction { prune(preferences.values.first()) } }

    suspend fun configure(value: StoragePreferences) = mutex.withLock {
        preferences.set(value)
        database.withTransaction { prune(value) }
    }

    suspend fun export(uri: Uri, redact: Boolean): Int = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "A document-provider URI is required." }
        mutex.withLock {
            database.withTransaction {
                prune(preferences.values.first())
                val count = dao.totalCount()
                val stream = app.contentResolver.openOutputStream(uri, "wt") ?: error("Document provider did not open a stream.")
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    TelemetryJson.writePaged(writer, count, redact, System.currentTimeMillis()) { offset -> dao.exportPage(offset) }
                }
                count
            }
        }
    }

    companion object {
        fun get(context: Context): TelemetryRepository = (context.applicationContext as AtlasApplication).telemetry
    }
}
