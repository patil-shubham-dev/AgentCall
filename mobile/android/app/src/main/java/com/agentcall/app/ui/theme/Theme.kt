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

// ── THE CONTROL BOARD — direction contract ──
// THESIS: The app is an industrial control panel — the user is the operator
// on watch, agents are equipment on the line, a call lights the board. It
// refuses the category default of dark navy, purple gradients and glass.
// OWN-WORLD: machined graphite panels with brushed-aluminum hairlines, LED
// status lamps (green lit / amber busy / red destructive / unlit idle),
// phosphor-cyan readouts, mono data, Black-weight industrial caps, per-agent
// identity ribbons. Flat machined plates, minimal radii, no glassmorphism.
// STORY: status is readable at a glance by lamp; the server line, agent
// presence and live calls read like instrument state.
// FIRST VIEWPORT: graphite Home — AGENTCALL plate header, line-status lamp,
// 2x2 grid of agent plates with lamps, engraved names, mono last-seen,
// machined Home/Settings keys.
// FORM: direction "The Control Board", seed key 8a6269ca, code-led (no comp;
// ambition carried by this contract, audited on device screenshots).
// FINISH: unreviewed and undocumented is unfinished; this build ends with
// the finish review, the verdict, DESIGN.md, and every shipping raster
// carrying its provenance.

// ── Dark Color Scheme ───────────────────────
private val DarkColorScheme = darkColorScheme(
    primary = Indigo500,
    onPrimary = Slate50,
    primaryContainer = Indigo800,
    onPrimaryContainer = Indigo200,
    secondary = Phosphor,
    onSecondary = Slate950,
    tertiary = Amber400,
    onTertiary = Slate950,
    background = Slate850,
    onBackground = Slate50,
    surface = Slate800,
    onSurface = Slate50,
    surfaceVariant = Slate750,
    onSurfaceVariant = Slate400,
    error = Red500,
    onError = Slate50,
    errorContainer = Red600,
    onErrorContainer = Slate50,
    outline = Slate600,
    outlineVariant = Slate700,
    inverseSurface = Slate200,
    inverseOnSurface = Slate900,
    inversePrimary = Indigo400,
    surfaceTint = Indigo500,
)

// ── Light Color Scheme ──────────────────────
private val LightColorScheme = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Indigo100,
    onPrimaryContainer = Indigo900,
    secondary = Indigo500,
    onSecondary = Color.White,
    tertiary = Amber600,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = Red600,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD4),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
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
