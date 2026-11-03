package com.example.atlas.ui.viz

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/** Holds the in-flight humps outside composition so a new scan retargets from what is on screen right now. */
private class SpectrumFrames { var tweens by mutableStateOf(emptyList<SpectrumMath.Tween>()) }

private const val HUMP_STEPS = 40

/**
 * V1: Wi-Fi spectrum. Each AP is a glowing hump centered on its occupied channel, as wide as its channel width and as
 * tall as its RSSI on a -100..-30 dBm axis. Band chips switch 2.4/5/6 GHz. Heights tween between scans and APs that
 * drop out fade away. Tapping a hump or a legend entry selects that AP ([onSelect]), which expands its list row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WifiSpectrum(aps: List<SpectrumAp>, selectedKey: String?, onSelect: (SpectrumAp) -> Unit, replay: Boolean = false) {
    if (aps.isEmpty()) {
        ChartCard("Wi-Fi spectrum", replay) {
            Text("No access points with signal readings yet. Start a Wi-Fi survey to populate the spectrum.",
                style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val band = chosen?.let { name -> SpectrumMath.Band.entries.firstOrNull { it.name == name } } ?: SpectrumMath.defaultBand(aps)
    val inBand = remember(aps, band) { aps.filter { SpectrumMath.bandOf(it.primaryMhz) == band } }
    val summary = SpectrumMath.summary(aps, band)
    val reduced = rememberReducedMotion()
    val dark = isDarkScheme()

    ChartCard("Wi-Fi spectrum", replay) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpectrumMath.Band.entries.forEach { b ->
                val count = aps.count { SpectrumMath.bandOf(it.primaryMhz) == b }
                FilterChip(selected = b == band, onClick = { chosen = b.name }, label = { Text("${b.label} · $count") })
            }
        }
        SpectrumCanvas(inBand, band, selectedKey, summary, reduced, dark, onSelect)
        if (inBand.isEmpty()) {
            Text("No access points with a signal reading in this band.", style = MaterialTheme.typography.bodySmall)
        } else {
            SpectrumLegend(inBand, selectedKey, dark, onSelect)
        }
        Text("Height is received signal (dBm), width is the reported channel width. Overlap shows shared airtime, not measured interference.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpectrumCanvas(
    aps: List<SpectrumAp>, band: SpectrumMath.Band, selectedKey: String?, summary: String,
    reduced: Boolean, dark: Boolean, onSelect: (SpectrumAp) -> Unit
) {
    val frames = remember(band) { SpectrumFrames() }
    val progress = remember(band) { Animatable(1f) }
    LaunchedEffect(aps, band, reduced) {
        frames.tweens = SpectrumMath.retarget(frames.tweens, progress.value, aps)
        if (reduced) progress.snapTo(1f) else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
        // Drop the faded-out humps once the tween has finished so the list does not grow across scans.
        frames.tweens = frames.tweens.filter { it.toAlpha > 0f }
    }
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val tickLabels = remember(band, labelStyle, measurer) {
        SpectrumMath.ticks(band).map { (channel, mhz) -> mhz to measurer.measure("$channel", labelStyle) }
    }
    val dbmLabels = remember(labelStyle, measurer) { listOf(-90, -70, -50).map { it to measurer.measure("$it", labelStyle) } }
    val density = LocalDensity.current
    val strokes = remember(density) {
        with(density) { Triple(Stroke(1.5.dp.toPx()), Stroke(3.dp.toPx()), Stroke(10.dp.toPx())) }
    }
    val leftPad = with(density) { 30.dp.toPx() }
    val bottomPad = with(density) { 18.dp.toPx() }
    val path = remember { Path() }
    val currentAps by rememberUpdatedState(aps)
    val currentSelect by rememberUpdatedState(onSelect)

    Canvas(
        Modifier.fillMaxWidth().height(200.dp)
            .semantics { contentDescription = summary }
            .pointerInput(band) {
                detectTapGestures { tap ->
                    val plotWidth = size.width - leftPad
                    if (plotWidth > 0f) SpectrumMath.hit((tap.x - leftPad) / plotWidth, currentAps, band)?.let(currentSelect)
                }
            }
    ) {
        val plotWidth = size.width - leftPad
        val base = size.height - bottomPad
        val plotHeight = base - 4f
        // Grid: dBm guide lines and channel ticks.
        dbmLabels.forEach { (dbm, layout) ->
            val y = base - SpectrumMath.height(dbm) * plotHeight
            drawLine(colors.outlineVariant, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f)
            drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
        }
        drawLine(colors.outline, Offset(leftPad, base), Offset(size.width, base), strokeWidth = 1.5f)
        tickLabels.forEach { (mhz, layout) ->
            val x = leftPad + SpectrumMath.x(mhz.toFloat(), band) * plotWidth
            drawLine(colors.outline, Offset(x, base), Offset(x, base + 4f), strokeWidth = 1.5f)
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, base + 5f))
        }
        val p = progress.value
        val tweens = frames.tweens
        // Selected and connected APs paint last so they sit on top of their neighbours.
        for (pass in 0..1) for (tween in tweens) {
            val emphasised = tween.ap.key == selectedKey || tween.ap.connected
            if ((pass == 1) != emphasised) continue
            val alpha = tween.alpha(p)
            val h = tween.height(p) * plotHeight
            if (alpha <= 0f || h <= 0.5f) continue
            val cx = leftPad + SpectrumMath.x(tween.ap.centerMhz.toFloat(), band) * plotWidth
            val half = tween.ap.widthMhz / 2f / band.spanMhz * plotWidth
            path.reset()
            path.moveTo(cx - half, base)
            for (i in 0..HUMP_STEPS) {
                val d = -1f + 2f * i / HUMP_STEPS
                path.lineTo(cx + d * half, base - SpectrumMath.profile(d) * h)
            }
            path.close()
            val color = seriesColor(tween.ap.key, dark)
            val selected = tween.ap.key == selectedKey
            drawPath(path, color.copy(alpha = alpha * (if (selected) 0.45f else 0.18f)))
            drawPath(path, color.copy(alpha = alpha * 0.16f), style = strokes.third)
            drawPath(path, color.copy(alpha = alpha), style = if (emphasised) strokes.second else strokes.first)
            if (tween.ap.connected) drawCircle(colors.primary, radius = 5f * density.density, center = Offset(cx, base - h), alpha = alpha)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpectrumLegend(aps: List<SpectrumAp>, selectedKey: String?, dark: Boolean, onSelect: (SpectrumAp) -> Unit) {
    val shown = remember(aps) { aps.sortedByDescending { it.rssiDbm }.take(LEGEND_MAX) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { ap ->
            val selected = ap.key == selectedKey
            Row(
                Modifier.clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = "Show this access point in the list") { onSelect(ap) }
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(seriesColor(ap.key, dark)))
                Text(buildString {
                    append(ap.label); append(" · "); append(ap.rssiDbm); append(" dBm")
                    ap.channel?.let { append(" · ch "); append(it) }
                    if (ap.connected) append(" · connected")
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (aps.size > shown.size) Text("+${aps.size - shown.size} more in the list below", style = MaterialTheme.typography.bodySmall)
    }
}

private const val LEGEND_MAX = 12
