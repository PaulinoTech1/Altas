package com.example.atlas

import com.example.atlas.acquisition.bluetooth.PhoneOrientation
import com.example.atlas.acquisition.bluetooth.PhoneOrientationMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneOrientationMatcherTest {
    private fun reading(time: Long) = PhoneOrientation(0f, 0f, 1f, time)

    @Test fun delayedBleCallbackUsesClosestEarlierReading() {
        val matcher = PhoneOrientationMatcher()
        matcher.record(reading(1_000_000_000L))
        matcher.record(reading(1_050_000_000L))
        matcher.record(reading(1_150_000_000L))
        assertEquals(1_050_000_000L, matcher.atOrBefore(1_100_000_000L)?.elapsedNanos)
    }

    @Test fun neverUsesFutureOrStaleReading() {
        val matcher = PhoneOrientationMatcher()
        matcher.record(reading(1_000_000_000L))
        matcher.record(reading(1_300_000_000L))
        assertNull(matcher.atOrBefore(999_999_999L))
        assertEquals(1_000_000_000L, matcher.atOrBefore(1_250_000_000L)?.elapsedNanos)
        assertNull(matcher.atOrBefore(1_250_000_001L))
    }

    @Test fun boundsHistoryAndRejectsInvalidReadings() {
        val matcher = PhoneOrientationMatcher(capacity = 2)
        matcher.record(reading(1L))
        matcher.record(reading(2L))
        matcher.record(reading(3L))
        matcher.record(PhoneOrientation(Float.NaN, 0f, 1f, 4L))
        assertNull(matcher.atOrBefore(1L))
        assertEquals(3L, matcher.atOrBefore(3L)?.elapsedNanos)
    }
}
