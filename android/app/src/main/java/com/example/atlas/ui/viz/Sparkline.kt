package com.example.atlas.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** V2: tiny RSSI trend (oldest to newest) on a fixed -100..-30 dBm scale so rows are comparable. */
@Composable
fun Sparkline(values: List<Int>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val stroke = remember(density) { with(density) { Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) } }
    val path = remember { Path() }
    val description = "RSSI trend, ${values.size} samples, ${values.min()} to ${values.max()} dBm, latest ${values.last()} dBm"
    Canvas(modifier.width(120.dp).height(28.dp).semantics { contentDescription = description }) {
        path.reset()
        val step = size.width / (values.size - 1)
        for (i in values.indices) {
            val y = size.height * (1f - VizMath.fraction(values[i].toFloat(), -100f, -30f))
            if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
        }
        drawPath(path, color, style = stroke)
    }
}
