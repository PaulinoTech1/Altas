package com.example.atlas.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.example.atlas.acquisition.bluetooth.BleObservation
import com.example.atlas.ui.TimeFormats
import kotlin.math.PI
import kotlin.math.roundToInt

private data class AxisGuide(val name: String, val x: Float, val y: Float, val z: Float, val color: Color)

/** The handset's observed orientation, with dBm encoded as a signal-strength shell, never transmitter location. */
@Composable
fun BleOrientationGlobe(
    history: List<BleObservation>, scanning: Boolean, selectedAddress: String?, startedAtMillis: Long?,
    onSelectAddress: (String?) -> Unit
) {
    val current = remember(history, scanning, startedAtMillis) {
        if (scanning && startedAtMillis != null) history.filter { it.observedAtMillis >= startedAtMillis } else history
    }
    val session = current.firstOrNull()?.orientationSessionId
    val samples = remember(current, session) {
        current.asSequence().filter {
            session != null && it.orientationSessionId == session && it.phoneOrientation != null && it.rssiDbm != null
        }.take(256).toList()
    }
    var yaw by remember(session) { mutableFloatStateOf(0.55f) }
    var pitch by remember(session) { mutableFloatStateOf(-0.35f) }
    var zoom by remember(session) { mutableFloatStateOf(1f) }
    var selectedEventId by remember(session) { mutableStateOf<String?>(null) }
    val projected = remember(samples, yaw, pitch, zoom) {
        samples.mapNotNull { OrientationGlobeMath.project(it, yaw, pitch, zoom) }
    }
    val activeAddress = selectedAddress?.takeIf { address -> samples.any { it.address == address } }
    val candidates = if (activeAddress == null) samples else samples.filter { it.address == activeAddress }
    val chosen = candidates.firstOrNull { it.eventId == selectedEventId } ?: candidates.firstOrNull()
    val chosenIndex = candidates.indexOfFirst { it.eventId == chosen?.eventId }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val axes = listOf(AxisGuide("X", 1f, 0f, 0f, colors.error),
        AxisGuide("Y", 0f, 1f, 0f, colors.tertiary), AxisGuide("Z", 0f, 0f, 1f, colors.primary))
    val textMeasurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurface)
    val tickLabels = remember(tickStyle, textMeasurer) {
        RadarMath.RINGS.dropLast(1).associateWith { textMeasurer.measure(it.toString(), tickStyle) }
    }
    val endLabels = remember(tickStyle, colors.error, colors.tertiary, colors.primary, textMeasurer) {
        axes.associate { it.name to textMeasurer.measure("+${it.name} -100", tickStyle.copy(color = it.color)) }
    }

    ChartCard("3D orientation signature") {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)
            .testTag("ble-orientation-globe")
            .semantics {
                contentDescription = "3D phone orientation signature, ${samples.size} paired BLE samples. " +
                    "Phone X, Y, Z axes have shared signal-strength ticks at -50, -70, -90, and -100 dBm. " +
                    "Not physical distance. Drag to orbit, pinch to zoom, tap a point to inspect."
                stateDescription = "View yaw ${(yaw * 180f / PI.toFloat()).roundToInt()} degrees, " +
                    "pitch ${(pitch * 180f / PI.toFloat()).roundToInt()} degrees, zoom ${"%.1f".format(zoom)} times"
            }
            .pointerInput(projected) {
                detectTapGestures { tap ->
                    val radius = minOf(size.width, size.height) * 0.43f
                    if (radius <= 0f) return@detectTapGestures
                    val x = (tap.x - size.width / 2f) / radius
                    val y = (tap.y - size.height / 2f) / radius
                    OrientationGlobeMath.hit(x, y, projected, 28.dp.toPx() / radius)?.event?.let {
                        selectedEventId = it.eventId
                        onSelectAddress(it.address)
                    }
                }
            }
            .pointerInput(session) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    yaw += pan.x / size.width * 3.2f
                    pitch = (pitch + pan.y / size.height * 3.2f).coerceIn(-1.4f, 1.4f)
                    zoom = (zoom * zoomChange).coerceIn(1f, 2.5f)
                }
            }) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.43f
            drawCircle(colors.surfaceVariant.copy(alpha = 0.28f), radius, center)
            for (tick in RadarMath.RINGS) {
                drawCircle(colors.outlineVariant.copy(alpha = 0.65f), radius * OrientationGlobeMath.signalRadius(tick) * zoom,
                    center, style = Stroke(1f))
            }
            for (axis in axes) {
                fun position(sign: Float, dbm: Int): Offset {
                    val point = OrientationGlobeMath.projectReference(axis.x * sign, axis.y * sign, axis.z * sign,
                        dbm, yaw, pitch, zoom)
                    return Offset(center.x + point.x * radius, center.y + point.y * radius)
                }
                val negative = position(-1f, -100)
                val positive = position(1f, -100)
                drawLine(axis.color.copy(alpha = 0.3f), negative, center, strokeWidth = 2f)
                drawLine(axis.color, center, positive, strokeWidth = 2.5f)
                for (tick in RadarMath.RINGS) {
                    val mark = position(1f, tick)
                    drawCircle(axis.color, 3f, mark)
                    if (axis.name == "X" && tick != -100) {
                        val label = tickLabels.getValue(tick)
                        drawText(label, topLeft = Offset(mark.x + 4f, mark.y + 4f))
                    }
                }
                val end = endLabels.getValue(axis.name)
                val labelY = if (axis.name == "X") positive.y + end.size.height + 6f else positive.y + 4f
                drawText(end, topLeft = Offset((positive.x + 4f).coerceIn(0f, (size.width - end.size.width).coerceAtLeast(0f)),
                    labelY.coerceIn(0f, (size.height - end.size.height).coerceAtLeast(0f))))
            }
            for (point in projected.sortedBy { it.depth }) {
                val event = point.event
                val position = Offset(center.x + point.x * radius, center.y + point.y * radius)
                val strength = ((event.rssiDbm!! + 100f) / 70f).coerceIn(0f, 1f)
                val depth = ((point.depth + 1f) / 2f).coerceIn(0f, 1f)
                val emphasis = if (activeAddress == null || event.address == activeAddress) 1f else 0.16f
                val color = seriesColor(event.address, dark)
                val dotRadius = 5f + strength * 5f
                drawCircle(color.copy(alpha = (0.35f + depth * 0.6f) * emphasis), dotRadius, position)
                if (event.eventId == chosen?.eventId) drawCircle(colors.onSurface, dotRadius + 5f, position, style = Stroke(2f))
            }
        }
        Text("Drag to orbit · pinch to zoom · tap a point. Touch targets extend beyond visible dots.",
            style = MaterialTheme.typography.bodySmall)
        Text("X, Y, Z are phone-orientation axes. Each colored axis has ticks at -50, -70, -90, -100 dBm; numbers on X label the shared scale. Stronger signal is toward center. Axes and shells are not device coordinates or range.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (selectedAddress != null && activeAddress == null && samples.isNotEmpty()) {
            Text("The selected address has no paired sample in this scan; showing all observed addresses.",
                style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selectedEventId = candidates[chosenIndex + 1].eventId },
                enabled = chosenIndex >= 0 && chosenIndex < candidates.lastIndex) { Text("Older point") }
            OutlinedButton(onClick = { selectedEventId = candidates[chosenIndex - 1].eventId },
                enabled = chosenIndex > 0) { Text("Newer point") }
            OutlinedButton(onClick = { yaw = 0.55f; pitch = -0.35f; zoom = 1f }) { Text("Reset view") }
            if (selectedAddress != null) OutlinedButton(onClick = { selectedEventId = null; onSelectAddress(null) }) {
                Text("Show all devices")
            }
        }
        if (chosen != null) {
            Text("Selected point ${chosenIndex + 1}/${candidates.size}: ${chosen.rssiDbm} dBm · " +
                "${chosen.advertisement.name?.takeIf { it.isNotBlank() } ?: chosen.address} · ${TimeFormats.dateTime(chosen.observedAtMillis)}",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("No paired orientation samples yet. Rotate the phone during an explicit BLE scan; a nearby advertiser is also required.",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("${samples.size} paired samples from the latest scan · ${if (scanning) "collecting" else "idle"}. " +
            "Addresses can rotate. Neither this model nor RSSI measures device bearing or physical location.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
