package com.agentcall.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Minimal premium — warm paper + ink ──
// Light: paper bg, white cards, ink primary. Dark: true near-black, no graphite.
// One radius, one border, one accent (ink), semantic each ONE meaning.

private val DarkColorScheme = darkColorScheme(
    primary = DarkTextPrimary,
    onPrimary = DarkBg,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkTextPrimary,
    secondary = DarkTextSecondary,
    onSecondary = DarkBg,
    tertiary = Warning,
    onTertiary = Color.White,
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorBg,
    onErrorContainer = Error,
    outline = DarkBorder,
    outlineVariant = DarkBorderStrong,
    inverseSurface = LightBg,
    inverseOnSurface = LightTextPrimary,
    inversePrimary = Ink,
    surfaceTint = DarkBorder,
)

private val LightColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = OnInk,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightTextPrimary,
    secondary = LightTextSecondary,
    onSecondary = Color.White,
    tertiary = Warning,
    onTertiary = Color.White,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorBg,
    outline = LightBorder,
    outlineVariant = LightBorderStrong,
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkTextPrimary,
    inversePrimary = Ink,
    surfaceTint = LightBorder,
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
    val indigo300: Color = Indigo300,
    val indigo400: Color = Indigo400,
    val slate300: Color = Slate300,
    val slate400: Color = Slate400,
    val slate700: Color = Slate700,
    val slate750: Color = Slate750,
    val slate850: Color = Slate850,
    val lightBackground: Color = LightBackground,
    val lightSurface: Color = LightSurface,
    val lightSurfaceVariant: Color = LightSurfaceVariant,
    val lightOnBackground: Color = LightOnBackground,
    val lightOnSurface: Color = LightOnSurface,
    val lightOnSurfaceVariant: Color = LightOnSurfaceVariant,
    val isDark: Boolean = true,
)

val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors() }

val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current

// ── Theme Composition ───────────────────────
@Composable
fun AgentCallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) {
        ExtendedColors(isDark = true)
    } else {
        ExtendedColors(
            surfaceElevated = LightSurfaceElevated,
            surfaceDeep = LightSurfaceVariant,
            textSecondary = LightOnSurfaceVariant,
            textTertiary = LightOutline,
            glassWhite = Color(0x0A000000),
            glassWhiteLight = Color(0x14000000),
            glassWhiteMedium = Color(0x26000000),
            glassIndigo = Color(0x146366F1),
            glassRed = Color(0x14EF4444),
            glassGreen = Color(0x1422C55E),
            glassAmber = Color(0x14F59E0B),
            slate300 = LightOutline,
            slate400 = LightOnSurfaceVariant,
            slate700 = LightSurfaceVariant,
            slate750 = LightSurfaceElevated,
            slate850 = LightSurfaceVariant,
            isDark = false,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = {
            CompositionLocalProvider(
                LocalExtendedColors provides extendedColors,
                content = content,
            )
        },
    )
}
