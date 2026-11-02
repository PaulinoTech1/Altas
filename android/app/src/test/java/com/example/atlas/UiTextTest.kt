package com.example.atlas

import com.example.atlas.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UiTextTest {
    private val us = Locale.US

    @Test fun pluralizesAndGroupsCounts() {
        assertEquals("1 day", UiText.days(1, us))
        assertEquals("7 days", UiText.days(7, us))
        assertEquals("0 days", UiText.days(0, us))
        assertEquals("1,000 records", UiText.records(1000, us))
        assertEquals("10,000 records", UiText.records(10000, us))
        assertEquals("50,000 records", UiText.records(50000, us))
        assertEquals("1 record", UiText.records(1, us))
        assertEquals("10.000 records", UiText.records(10000, Locale.GERMANY))
    }

    @Test fun lastMeasurementReadsNaturally() {
        val now = 1_000_000_000L
        assertEquals("No measurements this session", UiText.lastMeasurement(null, now, null))
        assertEquals("Last measurement: 6:51 PM (just now)", UiText.lastMeasurement(now - 5_000, now, "6:51 PM"))
        assertEquals("Last measurement: 6:51 PM (3 min ago)", UiText.lastMeasurement(now - 180_000, now, "6:51 PM"))
        assertEquals("2 h ago", UiText.relativeAge(now - 7_200_000, now))
        assertEquals("1 day ago", UiText.relativeAge(now - 86_400_000, now))
        assertEquals("just now", UiText.relativeAge(now + 10_000, now))
    }

    @Test fun retentionResultSaysWhatHappened() {
        assertEquals("Retention applied: nothing removed", UiText.retentionResult(0, us))
        assertEquals("Retention applied: 1 record removed", UiText.retentionResult(1, us))
        assertEquals("Retention applied: 1,200 records removed", UiText.retentionResult(1200, us))
    }

    @Test fun recordEnumsBecomeReadablePhrases() {
        assertEquals("Measured · recent platform report · access granted",
            UiText.recordSummary("MEASURED", "RECENT_PLATFORM_REPORT", "GRANTED_AT_DELIVERY"))
        assertEquals("Permission denied · not a measurement · access denied at save",
            UiText.recordSummary("PERMISSION_DENIED", "NOT_A_MEASUREMENT", "DENIED_AT_SAVE"))
        assertEquals("cached — device-wide", UiText.freshness("Cached — device-wide"))
        assertEquals("some new state", UiText.freshness("SOME_NEW_STATE"))
        assertEquals("Wi-Fi", UiText.radioTitle("WIFI"))
        assertEquals("OTHER", UiText.radioTitle("OTHER"))
    }
}
