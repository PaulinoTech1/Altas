package com.example.atlas.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * B8: shared DateFormat instances instead of building one per row per recomposition. Keyed by style, locale and time
 * zone so a locale or zone change takes effect on the next format; synchronized because DateFormat is not thread-safe.
 */
object TimeFormats {
    private data class Key(val kind: Int, val date: Int, val time: Int, val locale: Locale, val zone: String)
    private val cache = HashMap<Key, DateFormat>()

    private fun format(kind: Int, date: Int, time: Int, millis: Long): String = synchronized(cache) {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        cache.getOrPut(Key(kind, date, time, locale, zone.id)) {
            when (kind) {
                0 -> DateFormat.getDateTimeInstance(date, time, locale)
                1 -> DateFormat.getDateInstance(date, locale)
                else -> DateFormat.getTimeInstance(time, locale)
            }.also { it.timeZone = zone }
        }.format(Date(millis))
    }

    fun dateTime(millis: Long, date: Int = DateFormat.SHORT, time: Int = DateFormat.MEDIUM) = format(0, date, time, millis)
    fun date(millis: Long, style: Int = DateFormat.MEDIUM) = format(1, style, 0, millis)
    fun time(millis: Long, style: Int = DateFormat.MEDIUM) = format(2, 0, style, millis)
}
