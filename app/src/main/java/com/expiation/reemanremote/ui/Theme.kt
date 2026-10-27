package com.expiation.reemanremote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Muted, low-chroma palette: near-neutral greys with a slate-indigo accent. Every role is set so
// Material components (dialogs, switches, the nav indicator) never fall back to the baseline purple.
private val LightColors = lightColorScheme(
    primary = Color(0xFF34416E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2F2),
    onPrimaryContainer = Color(0xFF141C36),
    inversePrimary = Color(0xFFA9B6E0),
    secondary = Color(0xFF555D6E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E5F1),
    onSecondaryContainer = Color(0xFF1A2133),
    tertiary = Color(0xFF574A85),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7E2F5),
    onTertiaryContainer = Color(0xFF211938),
    background = Color(0xFFF3F4F6),
    onBackground = Color(0xFF15181D),
    surface = Color.White,
    onSurface = Color(0xFF15181D),
    surfaceVariant = Color(0xFFE8EAEE),
    onSurfaceVariant = Color(0xFF5B616D),
    surfaceTint = Color.Transparent, // flat surfaces: no tint creeping in with elevation
    inverseSurface = Color(0xFF2A2D33),
    inverseOnSurface = Color(0xFFEEF0F3),
    error = Color(0xFFA8392F),
    onError = Color.White,
    errorContainer = Color(0xFFF6DEDA),
    onErrorContainer = Color(0xFF410E0A),
    outline = Color(0xFFC3C8D0),
    outlineVariant = Color(0xFFE1E4E9),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDADDE2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF0F1F4),
    surfaceContainerHigh = Color(0xFFEAECF0),
    surfaceContainerHighest = Color(0xFFE4E6EB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9B6E0),
    onPrimary = Color(0xFF141C36),
    primaryContainer = Color(0xFF222B45),
    onPrimaryContainer = Color(0xFFD6DCF2),
    inversePrimary = Color(0xFF3F4C7E),
    secondary = Color(0xFFA3ABBD),
    onSecondary = Color(0xFF1A1F2A),
    secondaryContainer = Color(0xFF202A42), // the selected tab's pill
    onSecondaryContainer = Color(0xFFD6DCF2),
    tertiary = Color(0xFFB8AEDA),
    onTertiary = Color(0xFF221C38),
    tertiaryContainer = Color(0xFF2A2342),
    onTertiaryContainer = Color(0xFFE0DAF4),
    background = Color(0xFF0B0D11),
    onBackground = Color(0xFFE3E5E9),
    surface = Color(0xFF13161B),
    onSurface = Color(0xFFE3E5E9),
    surfaceVariant = Color(0xFF1B1F26),
    onSurfaceVariant = Color(0xFF8F96A3),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE3E5E9),
    inverseOnSurface = Color(0xFF1A1D22),
    error = Color(0xFFE0857B),
    onError = Color(0xFF3A1210),
    errorContainer = Color(0xFF3D1E1B),
    onErrorContainer = Color(0xFFF2D2CD),
    outline = Color(0xFF2C313B),
    outlineVariant = Color(0xFF1D2128),
    surfaceBright = Color(0xFF262A32),
    surfaceDim = Color(0xFF0B0D11),
    surfaceContainerLowest = Color(0xFF08090C),
    surfaceContainerLow = Color(0xFF101317),
    surfaceContainer = Color(0xFF15181E),
    surfaceContainerHigh = Color(0xFF1B1F26),
    surfaceContainerHighest = Color(0xFF22262E),
)

/** Meaningful colours (state, not decoration). "Container" is the fill, the plain one is text on it. */
@Immutable
data class StatusColors(
    val good: Color, val goodContainer: Color,
    val warn: Color, val warnContainer: Color,
    val danger: Color, val dangerContainer: Color,
    val offline: Color, // the Ping icon while the robot is not answering
    val demo: Color, val demoContainer: Color, // demo is also the DEMO strip's text, demoContainer its fill
    val demoBold: Color, // fill for white text: active demo buttons and switches
    val stop: Color,
    val exit: Color, // the dock's Exit, leaving the Map page: its icon and label, never a fill
    val beta: Color, // BETA, top left: pastel blue, apart from DEMO's purple
)

private val LightStatus = StatusColors(
    good = Color(0xFF2E7A4E), goodContainer = Color(0xFFDCEDE2),
    warn = Color(0xFF94661C), warnContainer = Color(0xFFF3E7CF),
    danger = Color(0xFFA8392F), dangerContainer = Color(0xFFF6DEDA),
    offline = Color(0xFFB4612A),
    demo = Color(0xFF4E3F86), demoContainer = Color(0xFFE9E5F5),
    demoBold = Color(0xFF5B4B98),
    stop = Color(0xFFB3342C),
    exit = Color(0xFFB0520F), // 5.2:1 on the white dock
    beta = Color(0xFF2C4C73),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF7BC496), goodContainer = Color(0xFF1B3125),
    warn = Color(0xFFD6A75E), warnContainer = Color(0xFF382C17),
    danger = Color(0xFFE0857B), dangerContainer = Color(0xFF3D1E1B),
    offline = Color(0xFFD48A5A),
    demo = Color(0xFFB9ACE0), demoContainer = Color(0xFF241C3A),
    demoBold = Color(0xFF5B4B98), // same in both themes so white text stays readable
    stop = Color(0xFFB3342C), // STOP stays the same deep red in both themes
    exit = Color(0xFFEE9A4E), // 8.1:1 on the dark dock
    beta = Color(0xFFA9C7EC),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

object RemoteTheme {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}

@Composable
fun RemoteTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
