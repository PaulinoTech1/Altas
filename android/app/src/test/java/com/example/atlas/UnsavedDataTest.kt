package com.example.atlas

import com.example.atlas.telemetry.UnsavedData
import org.junit.Assert.assertEquals
import org.junit.Test

class UnsavedDataTest {
    @Test fun everythingIsUnsavedBeforeTheFirstSave() {
        assertEquals(3, UnsavedData.count(listOf(1L, 2L, 3L), null))
        assertEquals(0, UnsavedData.count(emptyList(), null))
    }

    @Test fun onlyObservationsNewerThanTheMarkerCount() {
        assertEquals(1, UnsavedData.count(listOf(10L, 20L, 30L), 20L))
        assertEquals(0, UnsavedData.count(listOf(10L, 20L), 20L))
        val counts = UnsavedData.counts(mapOf("BLUETOOTH" to listOf(5L, 15L), "NFC" to listOf(1L)), mapOf("BLUETOOTH" to 10L))
        assertEquals(mapOf("BLUETOOTH" to 1, "WIFI" to 0, "CELLULAR" to 0, "NFC" to 1), counts)
    }

    @Test fun markersAreTheNewestTimestampAndOnlyMoveForward() {
        val markers = UnsavedData.markers(mapOf("BLUETOOTH" to listOf(5L, 15L, 9L), "WIFI" to emptyList()))
        assertEquals(mapOf("BLUETOOTH" to 15L), markers)
        assertEquals(mapOf("BLUETOOTH" to 20L, "WIFI" to 7L),
            UnsavedData.merge(mapOf("BLUETOOTH" to 20L, "WIFI" to 7L), mapOf("BLUETOOTH" to 15L)))
        assertEquals(20L, UnsavedData.merge(mapOf("BLUETOOTH" to 20L), mapOf("BLUETOOTH" to 15L))["BLUETOOTH"])
        assertEquals(25L, UnsavedData.merge(mapOf("BLUETOOTH" to 20L), mapOf("BLUETOOTH" to 25L))["BLUETOOTH"])
    }

    @Test fun summaryNamesRadiosInAFixedOrder() {
        assertEquals("Nothing new since the last save", UnsavedData.summary(mapOf("BLUETOOTH" to 0)))
        assertEquals("Not saved yet: Bluetooth 12 · Wi-Fi 40",
            UnsavedData.summary(mapOf("WIFI" to 40, "BLUETOOTH" to 12, "NFC" to 0)))
    }
}
