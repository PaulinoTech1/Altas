package com.example.atlas.ui.viz

import com.example.atlas.acquisition.bluetooth.BleObservation
import kotlin.math.cos
import kotlin.math.sin

/** Pure projection and hit testing. Coordinates are normalized to the plot radius. */
object OrientationGlobeMath {
    data class Point(val event: BleObservation, val x: Float, val y: Float, val depth: Float)
    data class ReferencePoint(val x: Float, val y: Float, val depth: Float)

    /** Uses the same -30 to -100 dBm scale as the 2D radar; radius is signal strength, not distance. */
    fun signalRadius(dbm: Int): Float = 0.18f + 0.82f * RadarMath.radius(dbm)

    fun project(event: BleObservation, yaw: Float, pitch: Float, zoom: Float): Point? {
        val facing = event.phoneOrientation ?: return null
        val dbm = event.rssiDbm ?: return null
        if (!listOf(facing.x, facing.y, facing.z, yaw, pitch, zoom).all(Float::isFinite)) return null
        val reference = projectReference(facing.x, facing.y, facing.z, dbm, yaw, pitch, zoom)
        return Point(event, reference.x, reference.y, reference.depth)
    }

    /** Project a phone-orientation axis at a labeled RSSI shell. Coordinates are not transmitter positions. */
    fun projectReference(x: Float, y: Float, z: Float, dbm: Int, yaw: Float, pitch: Float, zoom: Float): ReferencePoint {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val rotatedX = x * cy + z * sy
        val rotatedZ = -x * sy + z * cy
        val rotatedY = y * cp - rotatedZ * sp
        val depth = y * sp + rotatedZ * cp
        val radius = signalRadius(dbm) * zoom.coerceIn(1f, 2.5f)
        return ReferencePoint(rotatedX * radius, -rotatedY * radius, depth)
    }

    /** Nearest visible sample inside an expanded touch target. Foreground wins when points coincide. */
    fun hit(x: Float, y: Float, points: List<Point>, slop: Float): Point? = points.asSequence()
        .map { it to ((it.x - x) * (it.x - x) + (it.y - y) * (it.y - y)) }
        .filter { it.second <= slop * slop }
        .minWithOrNull(compareBy<Pair<Point, Float>> { it.second }.thenByDescending { it.first.depth })?.first
}
