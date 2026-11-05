package com.example.atlas.acquisition.bluetooth

/** Keeps recent sensor readings so a delayed BLE callback can use the reading at its RF timestamp. */
class PhoneOrientationMatcher(private val capacity: Int = 512) {
    init { require(capacity > 0) }

    private val readings = ArrayDeque<PhoneOrientation>()

    @Synchronized
    fun record(reading: PhoneOrientation) {
        if (reading.elapsedNanos <= 0 || !reading.x.isFinite() || !reading.y.isFinite() || !reading.z.isFinite()) return
        readings.addLast(reading)
        if (readings.size > capacity) readings.removeFirst()
    }

    /** A reading after the advertisement is never used to describe the phone at receipt. */
    @Synchronized
    fun atOrBefore(observedElapsedNanos: Long, maxAgeNanos: Long = 250_000_000L): PhoneOrientation? {
        if (observedElapsedNanos <= 0 || maxAgeNanos < 0) return null
        return readings.asSequence()
            .filter { it.elapsedNanos <= observedElapsedNanos }
            .maxByOrNull { it.elapsedNanos }
            ?.takeIf { observedElapsedNanos - it.elapsedNanos <= maxAgeNanos }
    }
}
