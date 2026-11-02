package com.example.atlas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Layout rhythm for the single atlas-content LazyColumn: items are 16 dp apart; siblings inside one item are 8 dp
 * apart (never 0 dp); label/value pairs use [Pairs] at 4 dp.
 */
@Composable
fun ItemColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Tight 4 dp grouping for label/value lines such as "Hardware: … / State: …". */
@Composable
fun Pairs(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/** A bordered section for blocks that used to be bare text on the background (access, export, retention). */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Section title: titleMedium, exposed to TalkBack as a heading. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** Explanatory copy: quieter than data (bodyMedium, onSurfaceVariant). */
@Composable
fun Explanation(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A safety or legal notice cut to one sentence ([summary]); "Details" reveals the full original text ([details]) in its
 * place. With [showFull] (for example a permission rationale while the permission is missing) the full text is shown
 * and there is nothing to expand.
 */
@Composable
fun ExpandableNotice(summary: String, details: String, modifier: Modifier = Modifier, showFull: Boolean = false) {
    var expanded by rememberSaveable(details) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Explanation(if (showFull || expanded) details else summary)
        if (!showFull) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
    }
}

/** A collector/storage status line; a polite live region so TalkBack announces changes. */
@Composable
fun StatusText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** B1: access when everything is granted — one status line and the review button, wrapping on narrow screens. */
@Composable
fun AccessGrantedRow(line: String, action: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(line)
        action()
    }
}
