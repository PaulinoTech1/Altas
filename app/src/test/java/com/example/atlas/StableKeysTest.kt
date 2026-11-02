package com.example.atlas

import com.example.atlas.ui.StableKeys
import com.example.atlas.ui.TimeFormats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.DateFormat
import java.util.Locale
import java.util.TimeZone

class StableKeysTest {
    private data class Ap(val bssid: String?, val eventId: String)

    @Test fun prefersPrimaryAndSurvivesRescan() {
        val scan1 = listOf(Ap("aa", "e1"), Ap("bb", "e2"))
        val scan2 = listOf(Ap("bb", "e9"), Ap("aa", "e8"))
        val keys = { l: List<Ap> -> StableKeys.of(l, { it.bssid?.let { b -> "ap-$b" } }, { "ap-${it.eventId}" }) }
        assertEquals(listOf("ap-aa", "ap-bb"), keys(scan1))
        assertEquals(listOf("ap-bb", "ap-aa"), keys(scan2))
    }

    @Test fun duplicatesAndNullsNeverCollide() {
        val rows = listOf(Ap("aa", "e1"), Ap("aa", "e2"), Ap(null, "e3"), Ap("aa", "e1"), Ap(null, "e3"))
        val keys = StableKeys.of(rows, { it.bssid?.let { b -> "ap-$b" } }, { "ap-${it.eventId}" })
        assertEquals(listOf("ap-aa", "ap-e2", "ap-e3", "ap-e1", "ap-e3#2"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun formatsFollowLocaleAndZone() {
        val locale = Locale.getDefault(); val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("12:00:00 AM", TimeFormats.time(0).replace('\u202f', ' '))
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals("7:00 PM", TimeFormats.time(0, DateFormat.SHORT).replace('\u202f', ' '))
            assertEquals("Dec 31, 1969", TimeFormats.date(0))
        } finally { Locale.setDefault(locale); TimeZone.setDefault(zone) }
    }
}
