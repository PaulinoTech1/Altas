package com.example.atlas

import com.example.atlas.telemetry.RetentionMath
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionMathTest {
    @Test fun cutoffMatchesRepositoryPrune() {
        assertEquals(1_000_000_000L - 7 * 86_400_000L, RetentionMath.cutoffMillis(1_000_000_000L, 7))
    }

    @Test fun removalCountsExpiredThenExcess() {
        assertEquals(0, RetentionMath.removalCount(total = 500, expired = 0, maximumRows = 1000))
        assertEquals(120, RetentionMath.removalCount(total = 500, expired = 120, maximumRows = 1000))
        // 12,000 saved, 500 expired, cap 10,000: 500 expired plus 1,500 over the cap.
        assertEquals(2000, RetentionMath.removalCount(total = 12_000, expired = 500, maximumRows = 10_000))
        assertEquals(1500, RetentionMath.removalCount(total = 11_500, expired = 0, maximumRows = 10_000))
        assertEquals(0, RetentionMath.removalCount(total = 0, expired = 0, maximumRows = 1000))
    }

    @Test fun removalIsNeverNegativeOrMoreThanTotal() {
        assertEquals(10, RetentionMath.removalCount(total = 10, expired = 50, maximumRows = 1000))
        assertEquals(0, RetentionMath.removalCount(total = 10, expired = -3, maximumRows = 1000))
    }
}
