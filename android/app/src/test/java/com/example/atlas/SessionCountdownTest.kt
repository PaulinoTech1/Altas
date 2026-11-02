package com.example.atlas

import com.example.atlas.ui.SessionCountdown
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCountdownTest {
    @Test fun remainingIsClampedToTheSession() {
        assertEquals(20_000L, SessionCountdown.remainingMillis(1_000, 20_000, 1_000))
        assertEquals(12_000L, SessionCountdown.remainingMillis(1_000, 20_000, 9_000))
        assertEquals(0L, SessionCountdown.remainingMillis(1_000, 20_000, 60_000))
        // A clock read before the start (should not happen) never shows more than the limit.
        assertEquals(20_000L, SessionCountdown.remainingMillis(1_000, 20_000, 0))
    }

    @Test fun clockRoundsUpToWholeSeconds() {
        assertEquals("0:20", SessionCountdown.clock(20_000))
        assertEquals("0:12", SessionCountdown.clock(11_001))
        assertEquals("1:00", SessionCountdown.clock(60_000))
        assertEquals("0:01", SessionCountdown.clock(1))
        assertEquals("0:00", SessionCountdown.clock(0))
        assertEquals("0:00", SessionCountdown.clock(-5))
        assertEquals("Scanning · 0:12 left", SessionCountdown.line("Scanning", 12_000))
    }

    @Test fun fractionTracksElapsedTime() {
        assertEquals(0f, SessionCountdown.elapsedFraction(0, 60_000, 0), 0.0001f)
        assertEquals(0.5f, SessionCountdown.elapsedFraction(0, 60_000, 30_000), 0.0001f)
        assertEquals(1f, SessionCountdown.elapsedFraction(0, 60_000, 90_000), 0.0001f)
        assertEquals(1f, SessionCountdown.elapsedFraction(0, 0, 10), 0.0001f)
    }

    @Test fun ticksLandOnTheNextWholeSecond() {
        assertEquals(1000L, SessionCountdown.millisToNextTick(5_000, 5_000))
        assertEquals(750L, SessionCountdown.millisToNextTick(5_000, 5_250))
        assertEquals(1L, SessionCountdown.millisToNextTick(5_000, 5_999))
        assertEquals(1000L, SessionCountdown.millisToNextTick(5_000, 4_000))
    }
}
