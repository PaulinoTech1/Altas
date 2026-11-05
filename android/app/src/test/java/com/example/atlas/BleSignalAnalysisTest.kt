package com.example.atlas

import com.example.atlas.acquisition.bluetooth.Advertisement
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.acquisition.bluetooth.BleSignalAnalysis
import com.example.atlas.acquisition.bluetooth.PhoneOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSignalAnalysisTest {
    private fun event(index: Int, dbm: Int?, x: Float = 1f, y: Float = 0f, z: Float = 0f,
                      address: String = "A", session: String = "current", paired: Boolean = true) =
        BleObservation("e$index", address, index.toLong(), index.toLong(), index.toLong(), dbm,
            false, Advertisement(), phoneOrientation = if (paired) PhoneOrientation(x, y, z, index.toLong()) else null,
            orientationSessionId = session, scanSequence = index.toLong())

    @Test fun singleStrongPacketCannotBecomeComparableSector() {
        val result = BleSignalAnalysis.summarize(listOf(event(1, -30)), "A")
        assertEquals(1, result.pairedSamples)
        assertTrue(result.comparableSectors.isEmpty())
        assertFalse(result.repeatableCoverage)
    }

    @Test fun medianAndSpreadResistOneOutlierAndKeepSessionBoundary() {
        val history = listOf(event(5, -70), event(4, -71), event(3, -69), event(2, -20),
            event(1, -35, session = "previous"))
        val result = BleSignalAnalysis.summarize(history, "A")
        assertEquals(4, result.targetSamples)
        assertEquals(-69, result.sectors.single().medianDbm)
        assertEquals(2, result.sectors.single().spreadDb)
        assertEquals(1, result.comparableSectors.size)
    }

    @Test fun missingMeasurementsAndRetainedSequenceGapsAreVisible() {
        val history = listOf(event(5, -65), event(3, -66, paired = false),
            event(2, null), event(1, -40, address = "B"))
        val result = BleSignalAnalysis.summarize(history, "A")
        assertEquals(3, result.targetSamples)
        assertEquals(1, result.missingRssi)
        assertEquals(1, result.missingOrientation)
        assertEquals(1L, result.sequenceGapsAtLeast)
        assertEquals(1, result.pairedSamples)
    }

    @Test fun coverageRequiresFourRepeatedStableSectors() {
        val vectors = listOf(1f to 0f, 0f to 1f, -1f to 0f, 0f to -1f)
        val history = vectors.flatMapIndexed { sector, (x, z) ->
            (0..2).map { offset -> event(12 - (sector * 3 + offset), -70 - offset, x = x, z = z) }
        }.sortedByDescending { it.scanSequence }
        val result = BleSignalAnalysis.summarize(history, "A")
        assertEquals(4, result.comparableSectors.size)
        assertTrue(result.repeatableCoverage)
    }

    @Test fun fourElevationSectorsInOneDirectionAreNotFullSweepCoverage() {
        val elevations = listOf(0f, 0.6f, -0.6f, 4f)
        val history = elevations.flatMapIndexed { sector, y ->
            (0..2).map { offset -> event(12 - (sector * 3 + offset), -70, x = 1f, y = y) }
        }.sortedByDescending { it.scanSequence }
        val result = BleSignalAnalysis.summarize(history, "A")
        assertEquals(4, result.comparableSectors.size)
        assertFalse(result.repeatableCoverage)
    }
}
