package com.example.atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.atlas.R

enum class AtlasSection(val page: String, val label: String) {
    OVERVIEW("Dashboard", "Overview"), HISTORY("History", "History"),
    SETTINGS("Settings", "Settings"), PROJECT("Project", "Project")
}

/**
 * B6: Material 3 top app bar with the page or radio title (one line, so it does not grow at large font), an Up arrow on
 * radio screens (contentDescription "Back to dashboard") and the PREVIEW badge as a compact action. It collapses on
 * scroll (enterAlways) and returns as soon as the user scrolls back up. The brand subtitle moved to Project.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtlasTopBar(title: String, onUp: (() -> Unit)?, scrollBehavior: TopAppBarScrollBehavior) {
    TopAppBar(
        title = {
            Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
        },
        navigationIcon = {
            if (onUp != null) IconButton(onClick = onUp) { val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
                Icon(AtlasIcons.ArrowBack, contentDescription = "Back to dashboard",
                    modifier = Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f }) }
        },
        actions = {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(end = 12.dp)) {
                Text("PREVIEW", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
        scrollBehavior = scrollBehavior
    )
}

/** Active: a prominent card. Idle: a single quiet line. Both are polite live regions so TalkBack hears start and stop. */
@Composable
fun AtlasSessionBanner(active: Boolean, message: String) {
    val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (active) Surface(
        modifier = announce,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SESSION ACTIVE", style = MaterialTheme.typography.labelLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    } else Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = announce)
}

@Composable
fun AtlasNavigation(page: String, historyBadge: Boolean = false, navigate: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        AtlasSection.entries.forEach { section ->
            val selected = page == section.page || (section == AtlasSection.OVERVIEW && AtlasSection.entries.none { it.page == page })
            NavigationBarItem(selected = selected, onClick = { navigate(section.page) },
                icon = {
                    // B3: a dot on History while temporary measurements are newer than the last save. Tag unchanged.
                    BadgedBox(badge = {
                        if (section == AtlasSection.HISTORY && historyBadge) Badge(Modifier.semantics { contentDescription = "Unsaved measurements" })
                    }) {
                        NavigationGlyph(section, if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                label = { Text(section.label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("nav-${section.page.lowercase()}"))
        }
    }
}

@Composable
private fun NavigationGlyph(section: AtlasSection, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height; val stroke = 1.8.dp.toPx()
        when (section) {
            AtlasSection.OVERVIEW -> listOf(0.08f to 0.08f, 0.58f to 0.08f, 0.08f to 0.58f, 0.58f to 0.58f).forEach { (x, y) ->
                drawRect(color, Offset(w * x, h * y), Size(w * 0.32f, h * 0.32f), style = Stroke(stroke))
            }
            AtlasSection.HISTORY -> {
                drawCircle(color, w * 0.4f, center, style = Stroke(stroke))
                drawLine(color, center, Offset(w * 0.5f, h * 0.25f), stroke)
                drawLine(color, center, Offset(w * 0.7f, h * 0.6f), stroke)
            }
            AtlasSection.SETTINGS -> listOf(0.22f, 0.5f, 0.78f).forEachIndexed { index, y ->
                drawLine(color, Offset(w * 0.1f, h * y), Offset(w * 0.9f, h * y), stroke)
                drawCircle(color, w * 0.09f, Offset(w * if (index == 1) 0.7f else 0.32f, h * y))
            }
            AtlasSection.PROJECT -> {
                val path = Path().apply { moveTo(w * 0.15f, h * 0.1f); lineTo(w * 0.15f, h * 0.9f); moveTo(w * 0.15f, h * 0.15f); lineTo(w * 0.85f, h * 0.15f); lineTo(w * 0.7f, h * 0.45f); lineTo(w * 0.15f, h * 0.45f) }
                drawPath(path, color, style = Stroke(stroke))
            }
        }
    }
}

fun LazyListScope.projectContent() {
    item {
        ItemColumn {
            // B6: the brand line moved here from the old top bar.
            Text("ATLAS RF", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("On-device radio diagnostics", style = MaterialTheme.typography.bodySmall)
            SectionTitle("Built to grow")
            Explanation("Atlas uses separate acquisition modules and a shared telemetry format. New modules can be added without replacing your saved history.")
        }
    }
    item { ProjectFeature("Available in this preview", "BLE discovery and advertisements; Wi-Fi surveys; reported cellular measurements; foreground NFC reader; local saves, history and JSON export. Hardware results depend on permissions and what Android supplies.") }
    item { ProjectFeature("NFC reader · Tag discovery verified", "The Pixel detected an authorized YubiKey 5C NFC as NfcA and IsoDep in metadata-only mode. NDEF content reading and ISO-DEP APDU exchange remain unverified.") }
    item { ProjectFeature("Bluetooth GATT · Validation pending", "Service discovery, authorized reads and notifications are implemented. Physical GATT interactions still need an authorized peripheral for validation.") }
    item { ProjectFeature("LTE and dual SIM · Coverage pending", "NR measurements have been observed on this Pixel. LTE-specific measurements and two active SIMs still need suitable network conditions for verification.") }
    item { ProjectFeature("External instruments · Future", "Raspberry Pi, Arduino and USB instruments remain future work. No remote service or external-hardware connection is enabled.") }
    item {
        ItemColumn {
            Text("Build ${stringResource(R.string.atlas_version)}", style = MaterialTheme.typography.labelLarge)
            Explanation("Development preview. Collections are user initiated and stop when their radio screen or the app is left. Unsaved session histories are temporary.")
        }
    }
}

@Composable
private fun ProjectFeature(title: String, detail: String) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Explanation(detail)
        }
    }
}
