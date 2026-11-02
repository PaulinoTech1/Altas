package com.example.atlas.ui

import com.example.atlas.model.Availability
import com.example.atlas.model.RadioState

/**
 * B7: the one-word status shown as a chip on each Overview card. Pure so the precedence is unit-tested; the card
 * pairs it with an icon and color, but the word always carries the meaning.
 */
enum class RadioStatus(val label: String, val tone: Tone) {
    COLLECTING("Collecting", Tone.GOOD),
    READY("Ready", Tone.GOOD),
    PERMISSION_NEEDED("Permission needed", Tone.ATTENTION),
    OFF("Off", Tone.ATTENTION),
    STATE_ERROR("State unreadable", Tone.ATTENTION),
    UNAVAILABLE("Hardware unavailable", Tone.NEUTRAL),
    NOT_CHECKED("Not checked", Tone.NEUTRAL);

    enum class Tone { GOOD, ATTENTION, NEUTRAL }

    companion object {
        fun of(availability: Availability, state: RadioState, accessReady: Boolean, collecting: Boolean): RadioStatus = when {
            collecting -> COLLECTING
            availability == Availability.UNKNOWN -> NOT_CHECKED
            availability == Availability.UNAVAILABLE -> UNAVAILABLE
            state == RadioState.DISABLED -> OFF
            !accessReady || state == RadioState.PERMISSION_REQUIRED -> PERMISSION_NEEDED
            state == RadioState.ERROR -> STATE_ERROR
            else -> READY
        }

        /** "Available · Enabled · Last: 6:51 PM". NOT_QUERIED is normal on the Overview (Cellular reads state on its own screen). */
        fun summaryLine(availability: Availability, state: RadioState, lastFormatted: String?): String {
            val stateText = if (state == RadioState.NOT_QUERIED) "State read on open" else state.label
            val last = lastFormatted?.let { "Last: $it" } ?: "Nothing collected yet"
            return listOf(availability.label, stateText, last).joinToString(" · ")
        }
    }
}
