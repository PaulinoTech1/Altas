package com.example.atlas

import android.app.Application
import com.example.atlas.telemetry.TelemetryRepository

/** Application-owned storage survives Activity recreation without retaining an Activity. */
class AtlasApplication : Application() {
    val telemetry: TelemetryRepository by lazy { TelemetryRepository(this) }
}
