package com.example.atlas.ui.viz

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/** Required caption: the radar encodes received signal strength only. */
const val RADAR_CAPTION = "Rings show signal strength, not distance. Position is not location."

/**
 * V2: BLE radar. Stronger devices sit nearer the center; the angle is a stable hash of the address and carries no
 * direction. Dot size grows with log(observations). The sweep turns only while a scan runs (and never with animations
 * off). Tapping a dot selects that device, which expands its row below.
 */
@Composable
fun BleRadar(dots: List<RadarDot>, scanning: Boolean, selectedAddress: String?, onSelect: (RadarDot) -> Unit, replay: Boolean = false) {
    if (dots.isEmpty()) {
        ChartCard("Signal radar", replay) {
            Text(if (scanning) "Waiting for BLE signal observations." else "Start a BLE scan to populate the radar.",
                style = MaterialTheme.typography.bodySmall)
            Text(RADAR_CAPTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val reduced = rememberReducedMotion()
    ChartCard("Signal radar", replay) {
        if (scanning && !reduced) {
            val transition = rememberInfiniteTransition(label = "radar-sweep")
            val sweep by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
            RadarCanvas(dots, scanning, selectedAddress, onSelect) { sweep }
        } else {
            RadarCanvas(dots, scanning, selectedAddress, onSelect, null)
        }
        Text(RADAR_CAPTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RadarCanvas(
    dots: List<RadarDot>, scanning: Boolean, selectedAddress: String?, onSelect: (RadarDot) -> Unit, sweep: (() -> Float)?
) {
    val colors = MaterialTheme.colorScheme
    val dark = isDarkScheme()
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val ringLabels = remember(labelStyle, measurer) { RadarMath.RINGS.map { it to measurer.measure("$it dBm", labelStyle) } }
    val density = LocalDensity.current
    val ringStroke = remember(density) { Stroke(with(density) { 1.dp.toPx() }) }
    val selectStroke = remember(density) { Stroke(with(density) { 2.dp.toPx() }) }
    val summary = RadarMath.summary(dots, scanning)
    val currentDots by rememberUpdatedState(dots)
    val currentSelect by rememberUpdatedState(onSelect)
    val sweepColor = colors.primary

    Spacer(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .semantics { contentDescription = summary }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val r = minOf(size.width, size.height) / 2f
                    if (r <= 0f) return@detectTapGestures
                    val ux = (tap.x - size.width / 2f) / r
                    val uy = (tap.y - size.height / 2f) / r
                    RadarMath.hit(ux, uy, currentDots, 24.dp.toPx() / r)?.let(currentSelect)
                }
            }
            .drawWithCache {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                // Built once per size/color, not per frame: the sweep rotates this brush via rotate().
                val sweepBrush = Brush.sweepGradient(
                    0f to Color.Transparent, 0.85f to Color.Transparent, 1f to sweepColor.copy(alpha = 0.35f), center = c)
                onDrawBehind {
                    drawCircle(colors.surfaceVariant.copy(alpha = 0.35f), r, c)
                    for ((dbm, layout) in ringLabels) {
                        val rr = RadarMath.radius(dbm) * r
                        drawCircle(colors.outlineVariant, rr, c, style = ringStroke)
                        drawText(layout, topLeft = Offset(c.x + 4f, c.y - rr + 2f))
                    }
                    drawLine(colors.outlineVariant, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 1f)
                    drawLine(colors.outlineVariant, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 1f)
                    if (sweep != null) {
                        val degrees = sweep()
                        rotate(degrees, c) {
                            drawCircle(sweepBrush, r, c)
                            drawLine(sweepColor.copy(alpha = 0.7f), c, Offset(c.x + r, c.y), strokeWidth = 2f)
                        }
                    }
                    for (dot in dots) {
                        val p = Offset(c.x + dot.x * r, c.y + dot.y * r)
                        val radius = RadarMath.dotRadiusDp(dot.count) * density.density
                        val color = seriesColor(dot.address, dark)
                        drawCircle(color.copy(alpha = 0.25f), radius * 1.8f, p)
                        drawCircle(color, radius, p)
                        if (dot.address == selectedAddress) drawCircle(colors.onSurface, radius + 4f * density.density, p, style = selectStroke)
                    }
                }
            }
    )
}
