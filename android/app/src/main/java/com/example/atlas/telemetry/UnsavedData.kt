package com.example.atlas.telemetry

import com.example.atlas.ui.UiText

/**
 * B3: which temporary radio observations are newer than the last successful save. Markers are the newest observation
 * timestamp per radio ("BLUETOOTH", "WIFI", "CELLULAR", "NFC") included in a save; anything later counts as unsaved.
 * Pure so it stays JVM-testable. Saving itself still de-duplicates by event id; this only drives the UI cue.
 */
object UnsavedData {
    val radios = listOf("BLUETOOTH", "WIFI", "CELLULAR", "NFC")

    fun count(timestamps: Collection<Long>, savedThroughMillis: Long?): Int =
        if (savedThroughMillis == null) timestamps.size else timestamps.count { it > savedThroughMillis }

    fun counts(timestamps: Map<String, Collection<Long>>, savedThrough: Map<String, Long>): Map<String, Int> =
        radios.associateWith { radio -> count(timestamps[radio].orEmpty(), savedThrough[radio]) }

    /** Newest timestamp per radio in a snapshot; radios with nothing buffered get no marker. */
    fun markers(timestamps: Map<String, Collection<Long>>): Map<String, Long> =
        timestamps.mapNotNull { (radio, values) -> values.maxOrNull()?.let { radio to it } }.toMap()

    /** Markers only move forward, so a save of an older snapshot can never resurrect "unsaved" items. */
    fun merge(previous: Map<String, Long>, saved: Map<String, Long>): Map<String, Long> =
        (previous.keys + saved.keys).associateWith { maxOf(previous[it] ?: Long.MIN_VALUE, saved[it] ?: Long.MIN_VALUE) }

    /** History supporting line, e.g. "Not saved yet: Bluetooth 12 · Wi-Fi 40" or "Nothing new since the last save". */
    fun summary(counts: Map<String, Int>): String {
        val pending = radios.mapNotNull { radio -> counts[radio]?.takeIf { it > 0 }?.let { "${UiText.radioTitle(radio)} ${UiText.number(it)}" } }
        return if (pending.isEmpty()) "Nothing new since the last save" else "Not saved yet: ${pending.joinToString(" · ")}"
    }
}
