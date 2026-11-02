package com.example.atlas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Every role is explicit so nothing falls back to the M3 baseline (purple) palette: containers (SESSION ACTIVE banner),
// tertiary (warning DiagnosticNotice), error, surfaceContainer* (cards, dialogs, menus) and inverse* (snackbars).
// Tertiary is an amber palette on purpose: it backs warnings, which should not read as another shade of teal.
// Dynamic color stays off (USABLE_PREVIEW: native light/dark teal palette).
private val DarkColorScheme = darkColorScheme(
    primary = AtlasMint, onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005143), onPrimaryContainer = Color(0xFF8CF8E1),
    inversePrimary = AtlasTeal, surfaceTint = AtlasMint,
    secondary = Color(0xFFA8CFC5), onSecondary = Color(0xFF12362E),
    secondaryContainer = Color(0xFF244D43), onSecondaryContainer = Color(0xFFCCF5E8),
    tertiary = Color(0xFFF5BF48), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200), onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = AtlasNight, onBackground = Color(0xFFE1ECEB),
    surface = AtlasNight, onSurface = Color(0xFFE1ECEB),
    surfaceVariant = Color(0xFF233238), onSurfaceVariant = Color(0xFFBDCFD1),
    outline = Color(0xFF849997), outlineVariant = Color(0xFF3F4E52),
    inverseSurface = Color(0xFFE1ECEB), inverseOnSurface = Color(0xFF2A3538),
    scrim = Color.Black,
    surfaceDim = AtlasNight, surfaceBright = Color(0xFF333E42),
    surfaceContainerLowest = Color(0xFF081215), surfaceContainerLow = Color(0xFF152024),
    surfaceContainer = Color(0xFF192428), surfaceContainerHigh = Color(0xFF232E33),
    surfaceContainerHighest = Color(0xFF2E393D)
)

private val LightColorScheme = lightColorScheme(
    primary = AtlasTeal, onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2DB), onPrimaryContainer = Color(0xFF00201A),
    inversePrimary = Color(0xFF82D5BF), surfaceTint = AtlasTeal,
    secondary = Color(0xFF45685F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFC6EDE1), onSecondaryContainer = Color(0xFF072C23),
    tertiary = Color(0xFF7A5900), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA6), onTertiaryContainer = Color(0xFF261900),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = AtlasPaper, onBackground = Color(0xFF152321),
    surface = AtlasPaper, onSurface = Color(0xFF152321),
    surfaceVariant = Color(0xFFDDE9E5), onSurfaceVariant = Color(0xFF3B4E49),
    outline = Color(0xFF6E827B), outlineVariant = Color(0xFFBFC9C5),
    inverseSurface = Color(0xFF2A3533), inverseOnSurface = Color(0xFFECF2F0),
    scrim = Color.Black,
    surfaceDim = Color(0xFFD5DBD9), surfaceBright = AtlasPaper,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEEF3F1),
    surfaceContainer = Color(0xFFE8EEEC), surfaceContainerHigh = Color(0xFFE3E9E7),
    surfaceContainerHighest = Color(0xFFDDE4E1)
)

@Composable
fun AtlasTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography, content = content)
}
