package com.example.atlas.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.LocalDate

fun radioColor(radio: String, scheme: ColorScheme): Color = when (radio) {
    "WIFI" -> scheme.primary
    "BLUETOOTH" -> scheme.tertiary
    "CELLULAR" -> scheme.secondary
    else -> scheme.outline
}

/**
 * V4: stacked bars of saved records per local day and radio (last 14 days) from a read-only aggregate query. Tapping a
 * segment sets the History radio filter to that radio; tapping the selected radio again clears it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryChart(bars: List<DayBar>, selectedRadio: String, onRadio: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val dayLabels = remember(bars, labelStyle, measurer) {
        bars.map { measurer.measure(LocalDate.ofEpochDay(it.epochDay).dayOfMonth.toString(), labelStyle) }
    }
    val maxTotal = bars.maxOfOrNull { it.total } ?: 0
    val maxLabel = remember(maxTotal, labelStyle, measurer) { measurer.measure("$maxTotal", labelStyle) }
    val summary = DailyCounts.summary(bars)
    val density = LocalDensity.current
    val bottomPad = with(density) { 16.dp.toPx() }
    val currentBars by rememberUpdatedState(bars)
    val currentRadio by rememberUpdatedState(selectedRadio)
    val currentOnRadio by rememberUpdatedState(onRadio)

    ChartCard("Saved records per day") {
        Canvas(
            Modifier.fillMaxWidth().height(140.dp)
                .semantics { contentDescription = summary }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val list = currentBars
                        val top = list.maxOfOrNull { it.total } ?: 0
                        if (list.isEmpty() || top == 0) return@detectTapGestures
                        val index = (tap.x / (size.width / list.size)).toInt().coerceIn(0, list.lastIndex)
                        val plot = size.height - bottomPad
                        DailyCounts.segmentAt(list[index], (plot - tap.y) / plot, top)?.let { radio ->
                            currentOnRadio(if (radio == currentRadio) "" else radio)
                        }
                    }
                }
        ) {
            val plot = size.height - bottomPad
            drawLine(scheme.outline, Offset(0f, plot), Offset(size.width, plot), strokeWidth = 1.5f)
            if (maxTotal == 0) return@Canvas
            drawText(maxLabel, topLeft = Offset(0f, 0f))
            val slot = size.width / bars.size
            val barWidth = slot * 0.62f
            for (i in bars.indices) {
                val bar = bars[i]
                val x = i * slot + (slot - barWidth) / 2f
                var y = plot
                for (radio in DailyCounts.RADIOS) {
                    val c = bar.counts[radio] ?: 0
                    if (c == 0) continue
                    val h = c.toFloat() / maxTotal * (plot - maxLabel.size.height)
                    val dimmed = selectedRadio.isNotEmpty() && selectedRadio != radio
                    drawRect(radioColor(radio, scheme), Offset(x, y - h), Size(barWidth, h), alpha = if (dimmed) 0.3f else 1f)
                    y -= h
                }
                val label = dayLabels[i]
                if (i % 2 == bars.lastIndex % 2) drawText(label, topLeft = Offset(i * slot + (slot - label.size.width) / 2f, plot + 2f))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DailyCounts.RADIOS.forEach { radio ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(radioColor(radio, scheme)))
                    Text(DailyCounts.TITLES.getValue(radio), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(if (maxTotal == 0) "No records saved in the last ${bars.size} days." else "Tap a bar segment to filter the list by that radio.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}
