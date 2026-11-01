package com.example.atlas.telemetry

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.map

private val Context.telemetryPreferences by preferencesDataStore("telemetry")

data class StoragePreferences(val retentionDays: Int = 7, val maximumRows: Int = 10000, val redactExport: Boolean = true) {
    init {
        require(retentionDays in listOf(1, 7, 30))
        require(maximumRows in listOf(1000, 10000, 50000))
    }
}

class TelemetryPreferences internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.telemetryPreferences)
    private val days = intPreferencesKey("retention_days")
    private val rows = intPreferencesKey("maximum_rows")
    private val redact = booleanPreferencesKey("redact_export")
    val values = store.data.map {
        StoragePreferences(it[days]?.takeIf { v -> v in listOf(1, 7, 30) } ?: 7,
            it[rows]?.takeIf { v -> v in listOf(1000, 10000, 50000) } ?: 10000, it[redact] ?: true)
    }
    suspend fun set(value: StoragePreferences) {
        store.edit { it[days] = value.retentionDays; it[rows] = value.maximumRows; it[redact] = value.redactExport }
    }
}
