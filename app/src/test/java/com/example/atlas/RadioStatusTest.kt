package com.example.atlas

import com.example.atlas.model.Availability
import com.example.atlas.model.RadioState
import com.example.atlas.ui.RadioStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioStatusTest {
    private fun of(a: Availability = Availability.AVAILABLE, s: RadioState = RadioState.ENABLED, access: Boolean = true, collecting: Boolean = false) =
        RadioStatus.of(a, s, access, collecting)

    @Test fun precedence() {
        assertEquals(RadioStatus.COLLECTING, of(s = RadioState.DISABLED, access = false, collecting = true))
        assertEquals(RadioStatus.NOT_CHECKED, of(a = Availability.UNKNOWN, access = false))
        assertEquals(RadioStatus.UNAVAILABLE, of(a = Availability.UNAVAILABLE))
        assertEquals(RadioStatus.OFF, of(s = RadioState.DISABLED, access = false))
        assertEquals(RadioStatus.PERMISSION_NEEDED, of(access = false))
        assertEquals(RadioStatus.PERMISSION_NEEDED, of(s = RadioState.PERMISSION_REQUIRED))
        assertEquals(RadioStatus.STATE_ERROR, of(s = RadioState.ERROR))
        assertEquals(RadioStatus.READY, of())
        assertEquals(RadioStatus.READY, of(s = RadioState.NOT_QUERIED))
    }

    @Test fun summaryLine() {
        assertEquals("Available · Enabled · Last: 6:51 PM", RadioStatus.summaryLine(Availability.AVAILABLE, RadioState.ENABLED, "6:51 PM"))
        assertEquals("Available · State read on open · Nothing collected yet",
            RadioStatus.summaryLine(Availability.AVAILABLE, RadioState.NOT_QUERIED, null))
    }
}
