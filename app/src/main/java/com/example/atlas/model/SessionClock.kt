package com.example.atlas.model

/**
 * Monotonic clock for session countdowns shown in the UI (B2). System.nanoTime is CLOCK_MONOTONIC on Android, the same
 * base the main-thread delay behind each session's withTimeout uses, and unlike SystemClock it also works in JVM unit
 * tests. Never used for measurement timestamps.
 */
object SessionClock {
    fun nowMillis(): Long = System.nanoTime() / 1_000_000
}
