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

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE4FF),
    onPrimaryContainer = Color(0xFF0A1F5C),
    secondaryContainer = Color(0xFFE3E8F2),
    onSecondaryContainer = Color(0xFF1E293B),
    background = Color(0xFFF3F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8ECF3),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB4FF),
    onPrimary = Color(0xFF0A1F5C),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBE4FF),
    secondaryContainer = Color(0xFF263041),
    onSecondaryContainer = Color(0xFFE2E8F0),
    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF151B26),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1F2733),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1F2937),
    error = Color(0xFFF87171),
)

/** Meaningful colours (state, not decoration). "Container" is the fill, the plain one is text on it. */
@Immutable
data class StatusColors(
    val good: Color, val goodContainer: Color,
    val warn: Color, val warnContainer: Color,
    val danger: Color, val dangerContainer: Color,
    val demo: Color, val demoContainer: Color,
    val demoBold: Color, // fill for white text: the DEMO strip and active demo chips
    val stop: Color,
)

private val LightStatus = StatusColors(
    good = Color(0xFF15803D), goodContainer = Color(0xFFDCFCE7),
    warn = Color(0xFFB45309), warnContainer = Color(0xFFFEF3C7),
    danger = Color(0xFFB91C1C), dangerContainer = Color(0xFFFEE2E2),
    demo = Color(0xFF7C3AED), demoContainer = Color(0xFFEDE9FE),
    demoBold = Color(0xFF7C3AED),
    stop = Color(0xFFDC2626),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF4ADE80), goodContainer = Color(0xFF14532D),
    warn = Color(0xFFFBBF24), warnContainer = Color(0xFF78350F),
    danger = Color(0xFFF87171), dangerContainer = Color(0xFF7F1D1D),
    demo = Color(0xFFA78BFA), demoContainer = Color(0xFF2E1065),
    demoBold = Color(0xFF7C3AED), // same in both themes so white text stays readable
    stop = Color(0xFFDC2626), // STOP stays the same saturated red in both themes
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
