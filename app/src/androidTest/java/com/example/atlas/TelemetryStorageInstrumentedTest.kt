package com.example.atlas

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.atlas.telemetry.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic storage fixtures, not RF or hardware measurement evidence. Never touches the production database. */
@RunWith(AndroidJUnit4::class)
class TelemetryStorageInstrumentedTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun row(key: String, at: Long, radio: String = "CELLULAR") = Measurement(key, radioType = radio,
        eventTimestampMillis = at, measurementType = "Synthetic storage fixture", value = if (key == "missing") null else -100.0,
        units = "dBm", collectionStatus = if (key == "missing") "UNAVAILABLE" else "MEASURED",
        permissionState = "SYNTHETIC_TEST", sourceApi = "Test fixture", freshness = "SYNTHETIC_TEST")

    @Test fun roomReopenPreservesNullableValuesAndDeduplicates() = runBlocking {
        val name = "telemetry-test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(app, TelemetryDatabase::class.java, name).build()
        try {
            assertEquals(2, db.measurements().insert(listOf(row("one", 100), row("missing", 200))).count { it != -1L })
            db.close()
            db = Room.databaseBuilder(app, TelemetryDatabase::class.java, name).build()
            assertEquals(listOf(-1L), db.measurements().insert(listOf(row("one", 100))))
            val rows = db.measurements().exportRows()
            assertEquals(2, rows.size); assertNull(rows.single { it.dedupeKey == "missing" }.value)
            assertEquals("UNAVAILABLE", rows.single { it.dedupeKey == "missing" }.collectionStatus)
        } finally { db.close(); app.deleteDatabase(name) }
    }

    @Test fun transactionalRetentionKeepsNewestAndHistoryFilters() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, TelemetryDatabase::class.java).build()
        try {
            db.withTransaction {
                db.measurements().insert((1..210).map { row("r$it", it.toLong(), if (it % 2 == 0) "WIFI" else "CELLULAR") })
                db.measurements().expire(5)
                db.measurements().cap(205)
            }
            assertEquals(205, db.measurements().count().first())
            assertEquals(210L, db.measurements().exportRows().first().eventTimestampMillis)
            assertEquals(6L, db.measurements().exportRows().last().eventTimestampMillis)
            assertEquals(200, db.measurements().history("", "fixture").first().size)
            assertEquals(5, db.measurements().history("", "fixture", 200).first().size)
            assertTrue(db.measurements().history("WIFI", "").first().all { it.radioType == "WIFI" })
            assertTrue(db.measurements().history("", "does-not-exist").first().isEmpty())
        } finally { db.close() }
    }

    @Test fun failedTransactionDoesNotLeavePartiallySavedBatch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, TelemetryDatabase::class.java).build()
        try {
            try { db.withTransaction { db.measurements().insert(listOf(row("one", 100))); error("Simulated interrupted save") } }
            catch (_: IllegalStateException) { }
            assertEquals(0, db.measurements().count().first())
        } finally { db.close() }
    }

    @Test fun preferencesSurviveReopenAndDefaultToRedaction() = runBlocking {
        val file = java.io.File(app.filesDir, "telemetry-test-${UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        try {
            var store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            var preferences = TelemetryPreferences(store)
            assertTrue(preferences.values.first().redactExport)
            preferences.set(StoragePreferences(30, 1000, false))
            job.cancelAndJoin()
            job = SupervisorJob()
            store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            preferences = TelemetryPreferences(store)
            assertEquals(StoragePreferences(30, 1000, false), preferences.values.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
