package com.example.atlas.ui.viz

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Exact wording required on every replayed chart and on the app-wide banner (V5). */
const val REPLAY_LABEL = "REPLAY of saved data — not live"

/**
 * True when the user turned animations off (Developer options or Accessibility "Remove animations" set the animator
 * duration scale to 0). Visuals then render their final state without looping or tweening.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Light/dark from the active scheme rather than the system flag, so a forced theme still gets readable chart colors. */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Stable per-key series color, darker in light mode so thin strokes stay readable on the paper background. */
fun seriesColor(key: String, dark: Boolean): Color =
    Color(0xff000000.toInt() or VizMath.hslToRgb(VizMath.stableHue(key), 0.7f, if (dark) 0.64f else 0.38f))

/**
 * Card around one chart: title, optional replay label, then the chart and any legend. The chart's own semantics carry
 * the summary; the text lists below each chart remain the full accessible data.
 */
@Composable
fun ChartCard(title: String, replay: Boolean = false, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (replay) ReplayTag()
            content()
        }
    }
}

/** The replay marker drawn on every replayed chart (V5). */
@Composable
fun ReplayTag() {
    Surface(color = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary,
        shape = MaterialTheme.shapes.small) {
        Text(REPLAY_LABEL, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
