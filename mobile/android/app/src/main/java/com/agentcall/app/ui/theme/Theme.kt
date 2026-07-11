package com.agentcall.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Dark Color Scheme ───────────────────────
private val DarkColorScheme = darkColorScheme(
    primary = Indigo500,
    onPrimary = Slate50,
    primaryContainer = Indigo700,
    onPrimaryContainer = Indigo200,
    secondary = Indigo400,
    onSecondary = Slate950,
    tertiary = Amber400,
    onTertiary = Slate950,
    background = Slate900,
    onBackground = Slate50,
    surface = Slate800,
    onSurface = Slate50,
    surfaceVariant = Slate700,
    onSurfaceVariant = Slate400,
    error = Red500,
    onError = Slate50,
    errorContainer = Red600,
    outline = Slate600,
    outlineVariant = Slate700,
    inverseSurface = Slate200,
    inverseOnSurface = Slate900,
    inversePrimary = Indigo600,
    surfaceTint = Indigo500,
)

// ── Extended Colors (accessible via composition) ──
@Immutable
data class ExtendedColors(
    val brandGradientStart: Color = GradientBrandStart,
    val brandGradientEnd: Color = GradientBrandEnd,
    val callGradientStart: Color = GradientCallStart,
    val callGradientEnd: Color = GradientCallEnd,
    val endGradientStart: Color = GradientEndStart,
    val endGradientEnd: Color = GradientEndEnd,
    val warningGradientStart: Color = GradientWarningStart,
    val warningGradientEnd: Color = GradientWarningEnd,
    val glassWhite: Color = GlassWhite,
    val glassWhiteLight: Color = GlassWhiteLight,
    val glassWhiteMedium: Color = GlassWhiteMedium,
    val glassIndigo: Color = GlassIndigo,
    val glassRed: Color = GlassRed,
    val glassGreen: Color = GlassGreen,
    val glassAmber: Color = GlassAmber,
    val surfaceElevated: Color = Slate750,
    val surfaceDeep: Color = Slate850,
    val textSecondary: Color = Slate400,
    val textTertiary: Color = Slate500,
    val success: Color = Green500,
    val warning: Color = Amber500,
    val error: Color = Red500,
    val waveformActive: Color = WaveformActive,
    val waveformIdle: Color = WaveformIdle,
    val waveformSpeaking: Color = WaveformSpeaking,
    val waveformMuted: Color = WaveformMuted,
    val indigo300: Color = Indigo300,
    val indigo400: Color = Indigo400,
    val slate300: Color = Slate300,
    val slate400: Color = Slate400,
    val slate700: Color = Slate700,
    val slate750: Color = Slate750,
    val slate850: Color = Slate850,
)

val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors() }

val MaterialTheme.extendedColors: ExtendedColors
    get() = LocalExtendedColors.current

// ── Theme Composition ───────────────────────
@Composable
fun AgentCallTheme(
    content: @Composable () -> Unit
) {
    val extendedColors = ExtendedColors()

    androidx.compose.material3.MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalExtendedColors provides extendedColors,
                content = content,
            )
        },
    )
}
