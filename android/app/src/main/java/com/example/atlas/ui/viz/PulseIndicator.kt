package com.example.atlas.ui.viz

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** What the pulse shows; derived from the existing RadioStatus by the caller. */
enum class PulseMode { COLLECTING, IDLE, INACTIVE }

/**
 * V4: Overview radio indicator. Collecting: an expanding, fading ring around a solid dot (static ring with animations
 * off). Idle and ready: a calm solid dot. Unavailable or not permitted: a grey dot. Decorative only; the status chip
 * next to it carries the words, so it is hidden from accessibility services.
 */
@Composable
fun PulseIndicator(mode: PulseMode, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val color = when (mode) {
        PulseMode.COLLECTING, PulseMode.IDLE -> scheme.primary
        PulseMode.INACTIVE -> scheme.outline
    }
    if (mode == PulseMode.COLLECTING && !reduced) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        PulseCanvas(color, modifier) { phase }
    } else {
        PulseCanvas(color, modifier, if (mode == PulseMode.COLLECTING) ({ 0.5f }) else null)
    }
}

@Composable
private fun PulseCanvas(color: Color, modifier: Modifier, phase: (() -> Float)?) {
    Canvas(modifier.size(20.dp).clearAndSetSemantics { }) {
        val dot = size.minDimension / 4f
        if (phase != null) {
            val p = phase()
            drawCircle(color, radius = dot + (size.minDimension / 2f - dot) * p, alpha = (1f - p) * 0.6f)
        }
        drawCircle(color, radius = dot)
    }
}
