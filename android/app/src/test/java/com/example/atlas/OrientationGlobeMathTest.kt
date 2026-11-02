package com.example.atlas

import com.example.atlas.acquisition.bluetooth.Advertisement
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.acquisition.bluetooth.PhoneOrientation
import com.example.atlas.ui.viz.OrientationGlobeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class OrientationGlobeMathTest {
    private fun observation(id: String, dbm: Int?, x: Float = 0f, y: Float = 0f, z: Float = 1f) =
        BleObservation(id, id, 1L, 1L, null, dbm, true, Advertisement(),
            phoneOrientation = PhoneOrientation(x, y, z, 1L), orientationSessionId = "scan")

    @Test fun dBmShellsUseRadarScaleWithoutCollapsingStrongPoints() {
        assertEquals(0.18f, OrientationGlobeMath.signalRadius(-30), 0.0001f)
        assertEquals(1f, OrientationGlobeMath.signalRadius(-100), 0.0001f)
        assertEquals(OrientationGlobeMath.signalRadius(-100), OrientationGlobeMath.signalRadius(-120), 0f)
    }

    @Test fun orbitChangesViewButDoesNotAlterObservation() {
        val event = observation("sample", -100)
        val front = OrientationGlobeMath.project(event, 0f, 0f, 1f)!!
        val side = OrientationGlobeMath.project(event, (PI / 2).toFloat(), 0f, 1f)!!
        assertEquals(0f, front.x, 0.001f)
        assertEquals(1f, front.depth, 0.001f)
        assertEquals(1f, side.x, 0.001f)
        assertEquals(0f, side.depth, 0.001f)
        assertEquals(1f, event.phoneOrientation!!.z, 0f)
    }

    @Test fun expandedHitTargetAndMissingValues() {
        val point = OrientationGlobeMath.project(observation("point", -70), 0f, 0f, 1f)!!
        assertEquals("point", OrientationGlobeMath.hit(point.x + 0.2f, point.y, listOf(point), 0.25f)?.event?.eventId)
        assertNull(OrientationGlobeMath.hit(point.x + 0.3f, point.y, listOf(point), 0.25f))
        assertNull(OrientationGlobeMath.project(observation("missing", null), 0f, 0f, 1f))
    }

    @Test fun xyzReferenceUsesSameDbmProjectionAsSamples() {
        val event = observation("x", -70, x = 1f, y = 0f, z = 0f)
        val point = OrientationGlobeMath.project(event, 0.55f, -0.35f, 1.5f)!!
        val axis = OrientationGlobeMath.projectReference(1f, 0f, 0f, -70, 0.55f, -0.35f, 1.5f)
        assertEquals(point.x, axis.x, 0.00001f)
        assertEquals(point.y, axis.y, 0.00001f)
        assertEquals(point.depth, axis.depth, 0.00001f)
        val weaker = OrientationGlobeMath.projectReference(1f, 0f, 0f, -90, 0.55f, -0.35f, 1.5f)
        assertEquals(true, weaker.x > axis.x)
    }
}
