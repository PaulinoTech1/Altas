package com.example.atlas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AtlasMint, onPrimary = Color(0xFF00382E),
    secondary = Color(0xFFA8CFC5), secondaryContainer = Color(0xFF244D43),
    onSecondaryContainer = Color(0xFFCCF5E8),
    background = AtlasNight, surface = AtlasNight,
    onBackground = Color(0xFFE1ECEB), onSurface = Color(0xFFE1ECEB),
    surfaceVariant = Color(0xFF233238), onSurfaceVariant = Color(0xFFBDCFD1),
    outline = Color(0xFF849997)
)

private val LightColorScheme = lightColorScheme(
    primary = AtlasTeal, onPrimary = Color.White,
    secondary = Color(0xFF45685F), secondaryContainer = Color(0xFFC6EDE1),
    onSecondaryContainer = Color(0xFF072C23),
    background = AtlasPaper, surface = AtlasPaper,
    onBackground = Color(0xFF152321), onSurface = Color(0xFF152321),
    surfaceVariant = Color(0xFFDDE9E5), onSurfaceVariant = Color(0xFF3B4E49),
    outline = Color(0xFF6E827B)
)

@Composable
fun AtlasTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography, content = content)
}
