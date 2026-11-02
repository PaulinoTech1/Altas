package com.example.atlas.telemetry

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Version 1: boot-relative times are milliseconds and only comparable within bootId. */
@Entity(tableName = "measurements", indices = [Index("eventTimestampMillis"), Index("radioType")])
data class Measurement(
    @PrimaryKey val dedupeKey: String,
    val eventId: String = UUID.randomUUID().toString(),
    val radioType: String,
    val eventTimestampMillis: Long,
    val sourceTimestampMillis: Long? = null,
    val sourceTimeBasis: String = "UNAVAILABLE",
    val receivedElapsedMillis: Long? = null,
    val bootId: String? = null,
    val interfaceId: String? = null,
    val measurementType: String,
    val value: Double? = null,
    val units: String? = null,
    val collectionStatus: String,
    val permissionState: String,
    val sourceApi: String,
    val freshness: String,
    val metadataJson: String = "{}"
)

@Dao
interface MeasurementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<Measurement>): List<Long>

    @Query("SELECT * FROM measurements WHERE (:radio = '' OR radioType = :radio) AND (measurementType LIKE '%' || :query || '%' OR collectionStatus LIKE '%' || :query || '%') ORDER BY eventTimestampMillis DESC, eventId DESC LIMIT 200 OFFSET :offset")
    fun history(radio: String, query: String, offset: Int = 0): Flow<List<Measurement>>

    @Query("SELECT COUNT(*) FROM measurements") fun count(): Flow<Int>
    @Query("SELECT COUNT(*) FROM measurements") suspend fun totalCount(): Int
    @Query("SELECT * FROM measurements ORDER BY eventTimestampMillis DESC, eventId DESC LIMIT 200 OFFSET :offset")
    suspend fun exportPage(offset: Int): List<Measurement>
    @Query("SELECT * FROM measurements ORDER BY eventTimestampMillis DESC, eventId DESC LIMIT 50000")
    suspend fun exportRows(): List<Measurement>
    @Query("DELETE FROM measurements WHERE eventTimestampMillis < :cutoff") suspend fun expire(cutoff: Long)
    /** B5 retention preview: read-only count of what [expire] would delete. */
    @Query("SELECT COUNT(*) FROM measurements WHERE eventTimestampMillis < :cutoff") suspend fun countOlderThan(cutoff: Long): Int
    @Query("DELETE FROM measurements WHERE dedupeKey NOT IN (SELECT dedupeKey FROM measurements ORDER BY eventTimestampMillis DESC, eventId DESC LIMIT :limit)")
    suspend fun cap(limit: Int)
}

@Database(entities = [Measurement::class], version = 1, exportSchema = true)
abstract class TelemetryDatabase : RoomDatabase() {
    abstract fun measurements(): MeasurementDao
}
