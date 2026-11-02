package com.example.atlas.ui.viz

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Theme colors for the quality bands, readable on light and dark surfaces. */
fun qualityColor(quality: SignalQuality, scheme: ColorScheme): Color = when (quality) {
    SignalQuality.POOR -> scheme.error
    SignalQuality.FAIR -> scheme.tertiary
    SignalQuality.GOOD -> scheme.secondary
    SignalQuality.EXCELLENT -> scheme.primary
}

/**
 * V3: 270° dials for the serving cell (or the subscription signal report) with colored quality bands and an animated
 * needle, then a live line of the primary metric from the bounded history. Missing values read "Not reported".
 */
@Composable
fun CellGauges(set: GaugeSet?, series: List<SeriesPoint>, replay: Boolean = false) {
    ChartCard("Signal gauges", replay) {
        if (set == null) {
            Text("No signal values reported yet.", style = MaterialTheme.typography.bodySmall)
            return@ChartCard
        }
        Text("${set.source} · ${set.rat}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            set.readings.forEach { reading -> Gauge(reading, Modifier.weight(1f)) }
        }
        set.primary?.let { primary ->
            LineChart(series, primary.spec, "${primary.spec.title} over time (${series.size} reports)")
        }
        Text("Bands are rough guides, not carrier thresholds.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Gauge(reading: GaugeReading, modifier: Modifier) {
    val reduced = rememberReducedMotion()
    val scheme = MaterialTheme.colorScheme
    val target = reading.value?.let { GaugeMath.sweep(it, reading.spec) } ?: 0f
    val needle = animateFloatAsState(target, if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 120f), label = "needle")
    val density = LocalDensity.current
    val track = remember(density) { Stroke(with(density) { 10.dp.toPx() }, cap = StrokeCap.Butt) }
    val bandColors = remember(scheme) {
        listOf(SignalQuality.POOR, SignalQuality.FAIR, SignalQuality.GOOD, SignalQuality.EXCELLENT).map { qualityColor(it, scheme) }
    }
    val spec = reading.spec
    val edges = remember(spec) {
        // Band boundaries as sweep angles: [start, e0, e1, e2, end].
        listOf(0f) + spec.edges.map { VizMath.fraction(it, spec.min, spec.max) * GaugeMath.SWEEP_DEGREES } + GaugeMath.SWEEP_DEGREES
    }
    val hasValue = reading.value != null
    Column(modifier.clearAndSetSemantics { contentDescription = GaugeMath.describe(reading) },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val inset = track.width / 2f + 2f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            for (i in 0 until 4) {
                drawArc(bandColors[i], GaugeMath.START_DEGREES + edges[i], edges[i + 1] - edges[i], false, topLeft, arcSize,
                    alpha = if (hasValue) 0.85f else 0.2f, style = track)
            }
            if (hasValue) {
                val angle = Math.toRadians((GaugeMath.START_DEGREES + needle.value).toDouble())
                val c = Offset(size.width / 2f, size.height / 2f)
                val len = arcSize.width / 2f - track.width
                drawLine(scheme.onSurface, c, Offset(c.x + (len * cos(angle)).toFloat(), c.y + (len * sin(angle)).toFloat()),
                    strokeWidth = 3f * density.density, cap = StrokeCap.Round)
                drawCircle(scheme.onSurface, 5f * density.density, c)
            }
        }
        Text(spec.title, style = MaterialTheme.typography.labelLarge)
        Text(reading.value?.let { "$it ${spec.unit}" } ?: "Not reported", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        reading.value?.let { Text(GaugeMath.quality(it, spec).label, style = MaterialTheme.typography.bodySmall,
            color = qualityColor(GaugeMath.quality(it, spec), scheme)) }
    }
}
