package com.agentcall.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── AgentCall dark identity ───────────────
// Deep charcoal ground, solid dark-grey surfaces, one violet accent for
// interactive, semantic green/red/amber each ONE meaning. Single source of
// truth; the legacy alias blocks below remap historical names onto these
// tokens so older screens follow the identity without per-screen edits.

// ── Base (dark) ───────────────────────────
val DarkBg = Color(0xFF0D0E12)            // deep charcoal, not pure black
val DarkSurface = Color(0xFF16181D)       // solid dark grey surfaces
val DarkSurfaceVariant = Color(0xFF23262E)
val DarkSurfaceElevated = Color(0xFF1D2026)
val DarkBorder = Color(0xFF2B2E37)
val DarkBorderStrong = Color(0xFF3B3F4B)
val DarkTextPrimary = Color(0xFFF4F5F7)
val DarkTextSecondary = Color(0xFFA4A8B3)
val DarkTextTertiary = Color(0xFF7B7F8A)

// ── Light base (secondary scheme; product is dark-first) ──
val LightBg = Color(0xFFFAFAFB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0F1F4)
val LightSurfaceElevated = Color(0xFFE8EAEE)
val LightBorder = Color(0xFFE3E5EA)
val LightBorderStrong = Color(0xFFD4D6DC)
val LightTextPrimary = Color(0xFF16181D)
val LightTextSecondary = Color(0xFF5A5E6A)
val LightTextTertiary = Color(0xFF8A8E99)

// ── Primary accent — AgentCall violet, interactive only ──
val BrandPurple = Color(0xFF7967DE)
val BrandPurpleLight = Color(0xFFA99EF2)
val BrandPurplePressed = Color(0xFF6A59CE)
// Second wave tint for the call aura (violet → warm magenta). Used only as a
// low-alpha ambient layer, never as a surface or control colour.
val BrandMagenta = Color(0xFFC06FB8)
val BrandPurpleDisabled = Color(0xFF2A2A2A)
val OnAccent = Color(0xFFFFFFFF)
// Violet-tinted fill for subtle active states (pills, selected rows).
val AccentTint = Color(0x337967DE)
val UserBubbleBg = DarkSurfaceElevated
val UserBubbleBorder = DarkBorder

// ── Ink (neutral interactive primary) ─────
val Ink = DarkTextPrimary
val InkPressed = Color(0xFFDBDCE0)
val OnInk = DarkBg

// ── Semantic (each ONE meaning) ───────────
val Success = Color(0xFF3BA874)       // connected / online / completed only
val SuccessBg = Color(0x1C3BA874)
val Warning = Color(0xFFD9A73E)       // reconnecting / offline / busy only
val WarningBg = Color(0x1CD9A73E)
val Error = Color(0xFFE5484D)         // destructive / failed only
val ErrorBg = Color(0x1CE5484D)
val Info = Color(0xFFA4A8B3)
val InfoBg = DarkSurfaceVariant
val Danger = Error
val SuccessText = Success
val WarningText = Warning

// ── Status dots ───────────────────────────
val DotOnline = Success
val DotBusy = Warning
val DotOffline = Color(0xFF4A4D57)
val DotReconnecting = Warning
val DotError = Error

// ── Legacy neutral aliases (compile compat — remapped) ──
val PanelDeep = DarkBg
val PanelBase = DarkSurface
val PanelRaised = DarkSurfaceVariant
val PanelRaisedAlt = DarkSurfaceElevated
val Hairline = DarkBorder
val HairlineWeak = DarkBorderStrong

val Slate50 = Color(0xFFFFFFFF)
val Slate100 = DarkTextPrimary
val Slate200 = Color(0xFFE7E8EC)
val Slate300 = DarkBorderStrong
val Slate400 = DarkTextSecondary
val Slate500 = DarkTextTertiary
val Slate600 = DarkTextTertiary
val Slate700 = DarkSurfaceVariant
val Slate750 = DarkSurfaceElevated
val Slate800 = DarkSurface
val Slate850 = Color(0xFF101116)
val Slate900 = DarkBg
val Slate950 = Color(0xFF090A0D)

val Indigo50 = Color(0xFFFFFFFF)
val Indigo100 = DarkTextPrimary
val Indigo200 = Color(0xFFE7E8EC)
val Indigo300 = DarkTextSecondary
val Indigo400 = DarkTextPrimary
val Indigo500 = Ink
val Indigo600 = Ink
val Indigo700 = InkPressed
val Indigo800 = Ink
val Indigo900 = DarkSurfaceVariant

val RibbonIndigo = Ink
val RibbonOrange = DarkTextSecondary
val RibbonCyan = DarkTextSecondary
val RibbonPink = DarkTextSecondary

val Phosphor = DarkTextPrimary
val WaveformActive = BrandPurpleLight
val WaveformMic = Success

// ── Call surface tokens ───────────────────
val CallSurface = DarkSurface
val CallSurfaceElevated = DarkSurfaceElevated
val CallBorder = DarkBorder
val CallBorderStrong = DarkBorderStrong

// Presence semantics (one color per state)
val PresenceListening = Success
val PresenceThinking = BrandPurpleLight
val PresenceSpeaking = BrandPurpleLight
val PresenceConnecting = Warning
val PresenceReconnecting = Warning
val PresenceMuted = DarkTextSecondary
val PresenceError = Error
val PresenceEnded = DarkTextTertiary

// Control surfaces
val ControlActive = BrandPurple
val ControlInactive = DarkSurfaceVariant
val ControlBorder = DarkBorder
val EndCallActive = Error

val GradientBrandStart = BrandPurple
val GradientBrandEnd = BrandPurpleLight
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
val Green600 = Color(0xFF2D8759)
val Red400 = Error
val Red500 = Error
val Red600 = Color(0xFFC93A3F)
val Amber300 = Color(0xFFE7C169)
val Amber400 = Warning
val Amber500 = Warning
val Amber600 = Color(0xFFB78827)

// ── Glass legacy (decorative removal — flattened to solid tints) ──
val GlassWhite = Color(0x14161818)
val GlassWhiteLight = Color(0x0F16181A)
val GlassWhiteMedium = Color(0x20161820)
val GlassIndigo = AccentTint
val GlassRed = ErrorBg
val GlassGreen = SuccessBg
val GlassAmber = WarningBg

val LightBackground = LightBg
val LightOnBackground = LightTextPrimary
val LightOnSurface = LightTextPrimary
val LightOnSurfaceVariant = LightTextSecondary
val LightOutline = LightBorder
val LightOutlineVariant = LightBorder
val LightInverseSurface = DarkSurface
val LightInverseOnSurface = DarkTextPrimary
val LightInversePrimary = BrandPurple

val LightTopBarSurface = LightSurface
val LightTopBarOnSurface = LightTextPrimary
