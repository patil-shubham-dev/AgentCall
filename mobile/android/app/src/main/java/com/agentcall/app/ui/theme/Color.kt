package com.agentcall.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Minimal premium (Claude-inspired): warm paper + ink ──
// One neutral stack, one primary (ink), semantic each ONE meaning.
// Light default; dark minimal (no competing indigo/cyan).

// ── Light base ────────────────────────────
val LightBg = Color(0xFFFAF9F7)           // warm paper
val LightSurface = Color(0xFFFFFFFF)      // card
val LightSurfaceVariant = Color(0xFFF2F2F0) // raised / input
val LightSurfaceElevated = Color(0xFFEBEBEA)
val LightBorder = Color(0xFFE8E8E6)       // hairline
val LightBorderStrong = Color(0xFFDDDDDB)
val LightTextPrimary = Color(0xFF111111)
val LightTextSecondary = Color(0xFF6B6B6B)
val LightTextTertiary = Color(0xFF9A9A9A)

// ── Dark base ─────────────────────────────
val DarkBg = Color(0xFF0F0F0F)
val DarkSurface = Color(0xFF1A1A1A)
val DarkSurfaceVariant = Color(0xFF232323)
val DarkSurfaceElevated = Color(0xFF2A2A2A)
val DarkBorder = Color(0xFF2C2C2E)
val DarkBorderStrong = Color(0xFF3A3A3C)
val DarkTextPrimary = Color(0xFFF5F5F3)
val DarkTextSecondary = Color(0xFF9A9A9A)
val DarkTextTertiary = Color(0xFF6B6B6B)

// ── Primary (ink) — ONE accent for interactive ──
val Ink = Color(0xFF111111)
val InkPressed = Color(0xFF000000)
val OnInk = Color(0xFFFAF9F7)

// ── Semantic (each ONE meaning) ───────────
val Success = Color(0xFF1A7F4B)       // online / completed only
val SuccessBg = Color(0xFFE8F5E9)
val Warning = Color(0xFFB45309)       // busy / reconnecting only
val WarningBg = Color(0xFFFEF3C7)
val Error = Color(0xFFDC2626)         // destructive / failed only
val ErrorBg = Color(0xFFFEE2E2)
val Info = Color(0xFF6B6B6B)          // informational only
val InfoBg = Color(0xFFF2F2F0)

// ── Dot lumps for lamps ───────────────────
val DotOnline = Success
val DotBusy = Warning
val DotOffline = Color(0xFFD1D1CF)
val DotReconnecting = Warning
val DotError = Error

// ── Legacy aliases (compile compat — remapped) ──
val PanelDeep = DarkBg
val PanelBase = DarkSurface
val PanelRaised = DarkSurfaceVariant
val PanelRaisedAlt = DarkSurfaceElevated
val Hairline = DarkBorder
val HairlineWeak = DarkBorderStrong

val Slate50 = Color(0xFFFAF9F7)
val Slate100 = Color(0xFFF2F2F0)
val Slate200 = Color(0xFFEBEBEA)
val Slate300 = Color(0xFFDDDDDB)
val Slate400 = LightTextSecondary
val Slate500 = Color(0xFF9A9A9A)
val Slate600 = DarkBorderStrong
val Slate700 = DarkBorder
val Slate750 = DarkSurfaceVariant
val Slate800 = DarkSurface
val Slate850 = DarkBg
val Slate900 = DarkBg
val Slate950 = Color(0xFF0A0A0A)

val Indigo50 = Color(0xFFFAF9F7)
val Indigo100 = Color(0xFFF2F2F0)
val Indigo200 = Color(0xFFE8E8E6)
val Indigo300 = LightTextSecondary
val Indigo400 = LightTextPrimary
val Indigo500 = Ink
val Indigo600 = Ink
val Indigo700 = InkPressed
val Indigo800 = Ink
val Indigo900 = LightSurfaceVariant

val RibbonIndigo = Ink
val RibbonOrange = Color(0xFF9A9A9A)
val RibbonCyan = LightTextSecondary
val RibbonPink = Color(0xFF9A9A9A)

val Phosphor = LightTextPrimary
val WaveformActive = LightTextSecondary

val GradientBrandStart = Ink
val GradientBrandEnd = LightTextSecondary
val GradientCallStart = Success
val GradientCallEnd = Success
val GradientEndStart = Error
val GradientEndEnd = Error
val GradientWarningStart = Warning
val GradientWarningEnd = Warning

val LampGreen = Success
val LampAmber = Warning
val LampRed = Error
val LampOff = DotOffline

val Green400 = Success
val Green500 = Success
val Green600 = Color(0xFF166534)
val Red400 = Error
val Red500 = Error
val Red600 = Color(0xFFB91C1C)
val Amber300 = Warning
val Amber400 = Warning
val Amber500 = Warning
val Amber600 = Color(0xFF92400E)

val BrandPurple = Color(0xFF7867DD)
val BrandPurplePressed = Color(0xFF6B5ACB)
val BrandPurpleDisabled = Color(0xFF2A2A2A)
val UserBubbleBg = Color(0xFF252036)
val UserBubbleBorder = Color(0xFF3D3558)

val GlassWhite = Color(0x0A111111)
val GlassWhiteLight = Color(0x0F111111)
val GlassWhiteMedium = Color(0x14111111)
val GlassIndigo = Color(0x0A111111)
val GlassRed = Color(0x0ADC2626)
val GlassGreen = Color(0x0A1A7F4B)
val GlassAmber = Color(0x0AB45309)

val LightBackground = LightBg
val LightOnBackground = LightTextPrimary
val LightOnSurface = LightTextPrimary
val LightOnSurfaceVariant = LightTextSecondary
val LightOutline = LightBorder
val LightOutlineVariant = LightBorder
val LightInverseSurface = DarkSurface
val LightInverseOnSurface = DarkTextPrimary
val LightInversePrimary = Ink