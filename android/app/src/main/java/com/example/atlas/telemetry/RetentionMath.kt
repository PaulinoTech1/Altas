package com.example.atlas.telemetry

/** B5: pure retention arithmetic for the "Remove up to N records permanently?" preview. Mirrors repository prune. */
object RetentionMath {
    const val DAY_MILLIS = 86_400_000L

    /** Records with eventTimestampMillis strictly before this are expired (same formula as prune). */
    fun cutoffMillis(nowMillis: Long, retentionDays: Int): Long = nowMillis - retentionDays * DAY_MILLIS

    /** Expired records first, then whatever still exceeds [maximumRows]. Never negative. */
    fun removalCount(total: Int, expired: Int, maximumRows: Int): Int {
        val expiredRemoved = expired.coerceIn(0, total.coerceAtLeast(0))
        return expiredRemoved + (total - expiredRemoved - maximumRows).coerceAtLeast(0)
    }
}
