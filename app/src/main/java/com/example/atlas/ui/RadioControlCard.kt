package com.example.atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.atlas.model.SessionClock
import kotlinx.coroutines.delay

/**
 * B1/B2: the first thing on every radio screen. While a session runs the card is a sticky header so Stop, the countdown
 * and live counts stay on screen while results scroll beneath it; while idle it is an ordinary first item so a tall
 * idle card (chips, permission hint) never covers results.
 */
fun LazyListScope.radioControlItem(active: Boolean, content: @Composable () -> Unit) {
    if (active) stickyHeader(key = "radio-control", contentType = "radio-control") { _: Int -> content() }
    else item(key = "radio-control", contentType = "radio-control") { content() }
}

/**
 * Control card body. [summary] holds the hardware/state lines; [actions] the Start/Stop button, secondary request,
 * configuration and status. [notice] is the session safety line ("… Leaving X stops …") or the idle line; it replaces
 * the page banner on radio screens and stays a polite live region so TalkBack hears start and stop.
 */
@Composable
fun RadioControlCard(
    active: Boolean,
    activity: String,
    startedAtMillis: Long?,
    limitMillis: Long,
    counts: String,
    notice: String,
    summary: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit
) {
    // Opaque so results scrolling under the pinned card never show through.
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shadowElevation = if (active) 3.dp else 0.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            summary()
            if (active) {
                SessionProgress(activity, startedAtMillis, limitMillis)
                Text(counts, style = MaterialTheme.typography.bodyMedium)
            }
            Text(notice, style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            actions()
        }
    }
}

/** "● Scanning · 0:12 left" plus a determinate bar. The 1 s tick lives here only, so it never recomposes the page. */
@Composable
private fun SessionProgress(activity: String, startedAtMillis: Long?, limitMillis: Long) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(SessionClock.nowMillis(), startedAtMillis, lifecycleOwner) {
        if (startedAtMillis != null) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = SessionClock.nowMillis()
                delay(SessionCountdown.millisToNextTick(startedAtMillis, value)) // Label only; never polls hardware.
            }
        }
    }
    val dotColor = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(dotColor) }
        Text(if (startedAtMillis == null) activity
            else SessionCountdown.line(activity, SessionCountdown.remainingMillis(startedAtMillis, limitMillis, now)),
            style = MaterialTheme.typography.titleMedium)
    }
    if (startedAtMillis != null) LinearProgressIndicator(
        progress = { SessionCountdown.elapsedFraction(startedAtMillis, limitMillis, now) },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * B1: limits and caveats sit after results under one expander. The texts are the full original notices, unchanged; they
 * only moved out of the control area.
 */
fun LazyListScope.aboutCollection(key: String, notices: List<String>) {
    item(key = key) {
        var expanded by rememberSaveable(key) { mutableStateOf(false) }
        ItemColumn {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide collection details" else "About this collection") }
            if (expanded) notices.forEach { Explanation(it) }
        }
    }
}
