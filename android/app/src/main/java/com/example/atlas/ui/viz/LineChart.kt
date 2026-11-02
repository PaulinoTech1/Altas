package com.example.atlas.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * V3: live line of one metric (oldest to newest) on its dial's range, with the quality band edges as guides. X is
 * report time, so gaps between reports stay visible rather than being closed up.
 */
@Composable
fun LineChart(points: List<SeriesPoint>, spec: GaugeSpec, title: String) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val stroke = remember(density) { with(density) { Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) } }
    val path = remember { Path() }
    Text(title, style = MaterialTheme.typography.labelLarge)
    if (points.size < 2) {
        Text("A line appears after two reports.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val description = "$title, from ${points.first().value} to ${points.last().value} ${spec.unit}, " +
        "range ${points.minOf { it.value }} to ${points.maxOf { it.value }} ${spec.unit}"
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        for (edge in spec.edges) {
            val y = size.height * (1f - VizMath.fraction(edge, spec.min, spec.max))
            drawLine(scheme.outlineVariant, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val t0 = points.first().atMillis
        val span = (points.last().atMillis - t0).coerceAtLeast(1L).toFloat()
        path.reset()
        for (i in points.indices) {
            val p = points[i]
            val x = size.width * ((p.atMillis - t0) / span)
            val y = size.height * (1f - GaugeMath.fraction(p.value, spec))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, scheme.primary, style = stroke)
        val last = points.last()
        drawCircle(scheme.primary, 4f * density.density,
            Offset(size.width, size.height * (1f - GaugeMath.fraction(last.value, spec))))
    }
}
