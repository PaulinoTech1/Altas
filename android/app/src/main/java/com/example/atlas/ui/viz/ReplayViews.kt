package com.example.atlas.ui.viz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import com.example.atlas.replay.ReplayFrame
import com.example.atlas.replay.ReplayState
import com.example.atlas.replay.ReplayTimeline
import com.example.atlas.ui.TimeFormats

/** V5: app-wide banner under the top bar while Demo replay is on. */
@Composable
fun ReplayBanner(state: ReplayState, turnOff: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(REPLAY_LABEL, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(replayPosition(state), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = turnOff) { Text("Turn off replay") }
        }
    }
}

fun replayPosition(state: ReplayState): String {
    val timeline = state.timeline
    val frame = state.frame
    return when {
        state.error != null -> state.error
        state.loading || timeline == null -> "Loading saved records…"
        frame == null -> "Nothing saved to replay."
        else -> "Saved ${TimeFormats.dateTime(frame.atMillis)} · step ${state.index + 1} of ${timeline.frames.size}"
    }
}

/** V5: Settings card with the Demo replay switch (off by default, not remembered across app restarts). */
@Composable
fun DemoReplaySettings(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().testTag("demo-replay").toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Replay saved data on charts", Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
        Text("Loops your own saved records, one step per second, on the Wi-Fi, Bluetooth and Cellular charts. Lists below the charts stay live. " +
            "Replay never collects, writes or exports anything and turns off when the app restarts.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * V5: replaces a live chart while replay is on: loading, an empty state when nothing relevant is saved, otherwise the
 * replayed chart (which carries the replay tag) plus the saved time of the step shown.
 */
@Composable
fun ReplayChart(state: ReplayState, title: String, has: (ReplayTimeline) -> Boolean, chart: @Composable (ReplayFrame) -> Unit) {
    val timeline = state.timeline
    val frame = state.frame
    if (state.loading || timeline == null || frame == null || !has(timeline)) {
        ChartCard(title, replay = true) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(when {
                state.error != null -> state.error
                state.loading || timeline == null -> "Loading saved records…"
                else -> "No saved $title data to replay. Save a session from this screen or History first. Replay never shows made-up data."
            }, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    chart(frame)
    Text(replayPosition(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
