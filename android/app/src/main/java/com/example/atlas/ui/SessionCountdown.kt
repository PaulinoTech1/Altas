package com.example.atlas.ui

/**
 * Pure countdown math for the radio control card (B2). Times are [com.example.atlas.model.SessionClock] millis.
 * No Android dependencies so it stays JVM-testable.
 */
object SessionCountdown {
    /** Milliseconds left in a session of [limitMillis] started at [startedAtMillis], clamped to 0..limit. */
    fun remainingMillis(startedAtMillis: Long, limitMillis: Long, nowMillis: Long): Long =
        (limitMillis - (nowMillis - startedAtMillis)).coerceIn(0L, limitMillis.coerceAtLeast(0L))

    /** Elapsed share of the session, 0f..1f, for a determinate progress bar. */
    fun elapsedFraction(startedAtMillis: Long, limitMillis: Long, nowMillis: Long): Float =
        if (limitMillis <= 0) 1f
        else ((limitMillis - remainingMillis(startedAtMillis, limitMillis, nowMillis)).toFloat() / limitMillis).coerceIn(0f, 1f)

    /** "m:ss", rounding up so a 20-second scan starts at "0:20" and shows "0:00" only when time is up. */
    fun clock(remainingMillis: Long): String {
        val seconds = ((remainingMillis.coerceAtLeast(0) + 999) / 1000)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** "Scanning · 0:12 left". */
    fun line(activity: String, remainingMillis: Long): String = "$activity · ${clock(remainingMillis)} left"

    /** Delay until the displayed whole second changes, so the countdown ticks on the second without drifting. */
    fun millisToNextTick(startedAtMillis: Long, nowMillis: Long): Long {
        val intoSecond = ((nowMillis - startedAtMillis) % 1000 + 1000) % 1000
        return (1000 - intoSecond).coerceIn(1, 1000)
    }
}
