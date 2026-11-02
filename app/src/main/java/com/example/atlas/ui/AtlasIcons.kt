package com.example.atlas.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The glyphs Atlas needs, drawn from the Material icon geometry (24 dp grid) so the app does not take on the
 * material-icons dependency. Icon() tints them with the current content color.
 */
object AtlasIcons {
    val Check: ImageVector by lazy {
        icon("Check") {
            moveTo(9f, 16.17f); lineTo(4.83f, 12f); lineToRelative(-1.42f, 1.41f)
            lineTo(9f, 19f); lineTo(21f, 7f); lineToRelative(-1.41f, -1.41f); close()
        }
    }

    /** Triangle with an exclamation mark (cut out with even-odd fill). */
    val Warning: ImageVector by lazy {
        icon("Warning") {
            moveTo(1f, 21f); lineTo(23f, 21f); lineTo(12f, 2f); close()
            moveTo(11f, 10f); horizontalLineToRelative(2f); verticalLineToRelative(4f); horizontalLineToRelative(-2f); close()
            moveTo(11f, 16f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
        }
    }

    /** Circle with an exclamation mark (cut out with even-odd fill). */
    val Error: ImageVector by lazy {
        icon("Error") {
            moveTo(2f, 12f)
            arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
            close()
            moveTo(11f, 7f); horizontalLineToRelative(2f); verticalLineToRelative(6f); horizontalLineToRelative(-2f); close()
            moveTo(11f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
        }
    }

    /** Up / back arrow (Material "arrow_back"); AtlasTopBar mirrors it in right-to-left layouts. */
    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack") {
            moveTo(20f, 11f); horizontalLineTo(7.83f); lineToRelative(5.59f, -5.59f); lineTo(12f, 4f)
            lineToRelative(-8f, 8f); lineToRelative(8f, 8f); lineToRelative(1.41f, -1.41f); lineTo(7.83f, 13f)
            horizontalLineTo(20f); verticalLineToRelative(-2f); close()
        }
    }

    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = block)
            .build()
}

/** Leading check for a selected FilterChip, so selection does not rely on fill color alone. */
fun chipCheck(selected: Boolean): (@Composable () -> Unit)? = if (selected) {
    { Icon(AtlasIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
} else null
