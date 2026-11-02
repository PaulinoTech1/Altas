package com.example.atlas.ui

import java.text.NumberFormat
import java.util.Locale

/** Pure copy-formatting helpers for UI labels; no Android dependencies so they stay JVM-testable. */
object UiText {
    /** Locale-grouped integer, e.g. 10000 -> "10,000" in en-US. */
    fun number(value: Int, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(value)

    /** "1 day", "7 days", "10,000 records". English plurals only; C4 moves these to plurals resources. */
    fun count(value: Int, singular: String, plural: String = "${singular}s", locale: Locale = Locale.getDefault()): String =
        "${number(value, locale)} ${if (value == 1) singular else plural}"

    fun days(value: Int, locale: Locale = Locale.getDefault()) = count(value, "day", locale = locale)
    fun records(value: Int, locale: Locale = Locale.getDefault()) = count(value, "record", locale = locale)

    /** Coarse relative age for labels that refresh at most every 30 seconds. */
    fun relativeAge(thenMillis: Long, nowMillis: Long): String {
        val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3_600} h ago"
            else -> count((seconds / 86_400).toInt(), "day") + " ago"
        }
    }

    /** Overview card line: "Last measurement: 6:51 PM (3 min ago)" or "No measurements this session". */
    fun lastMeasurement(thenMillis: Long?, nowMillis: Long, formattedTime: String?): String =
        if (thenMillis == null || formattedTime == null) "No measurements this session"
        else "Last measurement: $formattedTime (${relativeAge(thenMillis, nowMillis)})"

    fun retentionResult(removed: Int, locale: Locale = Locale.getDefault()): String =
        if (removed <= 0) "Retention applied: nothing removed" else "Retention applied: ${records(removed, locale)} removed"

    fun radioTitle(raw: String): String = when (raw) {
        "BLUETOOTH" -> "Bluetooth"; "WIFI" -> "Wi-Fi"; "CELLULAR" -> "Cellular"; "NFC" -> "NFC"
        else -> raw
    }

    /** Readable form of Measurement.collectionStatus; the raw value stays in "Inspect record". */
    fun collectionStatus(raw: String): String = when (raw) {
        "MEASURED" -> "Measured"
        "CACHED" -> "Cached"
        "UNAVAILABLE" -> "Unavailable"
        "PERMISSION_DENIED" -> "Permission denied"
        "UNSUPPORTED" -> "Unsupported"
        "FAILED" -> "Failed"
        else -> humanize(raw).replaceFirstChar { it.uppercase() }
    }

    /** Readable form of Measurement.freshness (normalizer enums, Wi-Fi enum names or cellular labels). */
    fun freshness(raw: String): String = when (raw) {
        "RECENT_PLATFORM_REPORT" -> "recent platform report"
        "RECENT" -> "recent platform update"
        "STALE" -> "stale"
        "CACHED" -> "cached"
        "UNKNOWN", "SOURCE_AGE_UNKNOWN" -> "source age unknown"
        "NOT_A_MEASUREMENT" -> "not a measurement"
        else -> humanize(raw).replaceFirstChar { it.lowercase() }
    }

    fun permissionState(raw: String): String = when (raw) {
        "GRANTED_AT_DELIVERY" -> "access granted"
        "GRANTED_AT_SAVE" -> "access granted at save"
        "DENIED_AT_SAVE" -> "access denied at save"
        else -> humanize(raw)
    }

    /** "Measured · recent platform report · access granted". */
    fun recordSummary(collectionStatus: String, freshness: String, permissionState: String): String =
        "${collectionStatus(collectionStatus)} · ${freshness(freshness)} · ${permissionState(permissionState)}"

    private fun humanize(raw: String): String =
        if (raw.any { it.isLowerCase() }) raw else raw.lowercase(Locale.ROOT).replace('_', ' ')
}
