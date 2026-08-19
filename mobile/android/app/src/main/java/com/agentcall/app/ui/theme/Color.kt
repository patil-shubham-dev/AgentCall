package com.agentcall.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── The Control Board ────────────────────────
// Machined graphite panels, brushed-aluminum hairlines, LED status lamps,
// phosphor readouts. The app is an industrial control panel: the user is the
// operator on watch, agents are equipment on the line, a call lights the board.

// ── Panel Surfaces (machined graphite) ───────
val PanelDeep = Color(0xFF0D0F12)      // app ground — the dark room
val PanelBase = Color(0xFF131519)      // base graphite
val PanelRaised = Color(0xFF1B1E23)    // plates and cards
val PanelRaisedAlt = Color(0xFF20242A) // pressed / raised-alt
val Hairline = Color(0xFF2E3238)       // brushed-aluminum border
val HairlineWeak = Color(0xFF3A3F46)   // hairline / unlit lamp

// ── Neutrals (kept names, remapped values) ──
val Slate50 = Color(0xFFF2F4F6)
val Slate100 = Color(0xFFE3E6EA)
val Slate200 = Color(0xFFCBD0D6)
val Slate300 = Color(0xFFAEB4BC)
val Slate400 = Color(0xFF8A8F98)
val Slate500 = Color(0xFF555B63)
val Slate600 = Color(0xFF3A3F46)
val Slate700 = Color(0xFF2E3238)
val Slate750 = Color(0xFF20242A)
val Slate800 = Color(0xFF1B1E23)
val Slate850 = Color(0xFF0D0F12)
val Slate900 = Color(0xFF131519)
val Slate950 = Color(0xFF05060A)

// ── Ribbon Indigo (primary accent) ───────────
val Indigo50 = Color(0xFFEEF1FF)
val Indigo100 = Color(0xFFDDE2FF)
val Indigo200 = Color(0xFFBCC6FF)
val Indigo300 = Color(0xFF9DAAFF)
val Indigo400 = Color(0xFF8FA2FF)
val Indigo500 = Color(0xFF6C7CFF)
val Indigo600 = Color(0xFF5567E8)
val Indigo700 = Color(0xFF3E4FC9)
val Indigo800 = Color(0xFF2A39A0)
val Indigo900 = Color(0xFF232B5C)

// ── Identity Ribbons (per-agent) ─────────────
val RibbonIndigo = Color(0xFF6C7CFF)
val RibbonOrange = Color(0xFFFF9A3D)
val RibbonCyan = Color(0xFF35E0FF)
val RibbonPink = Color(0xFFFF5FA2)

// ── Phosphor Readouts ────────────────────────
val Phosphor = Color(0xFF35E0FF)
val WaveformActive = Color(0xFF35E0FF)

// ── Accent Gradients (ribbon pairs) ──────────
val GradientBrandStart = Color(0xFF6C7CFF)
val GradientBrandEnd = Color(0xFF35E0FF)
val GradientCallStart = Color(0xFF3DDC84)
val GradientCallEnd = Color(0xFF2ECF76)
val GradientEndStart = Color(0xFFFF3B30)
val GradientEndEnd = Color(0xFFE02B22)
val GradientWarningStart = Color(0xFFFFB020)
val GradientWarningEnd = Color(0xFFF09300)

// ── Status Lamps ─────────────────────────────
val LampGreen = Color(0xFF3DDC84)
val LampAmber = Color(0xFFFFB020)
val LampRed = Color(0xFFFF3B30)
val LampOff = Color(0xFF3A3F46)

// ── Status Colors (kept names, remapped) ─────
val Green400 = Color(0xFF3DDC84)
val Green500 = Color(0xFF2ECF76)
val Green600 = Color(0xFF1FAE62)
val Red400 = Color(0xFFFF6B5E)
val Red500 = Color(0xFFFF3B30)
val Red600 = Color(0xFFE02B22)
val Amber300 = Color(0xFFFFD25E)
val Amber400 = Color(0xFFFFB020)
val Amber500 = Color(0xFFF09300)
val Amber600 = Color(0xFFD97E00)

// ── Plate Sheen (kept names, remapped) ───────
val GlassWhite = Color(0x0DFFFFFF)
val GlassWhiteLight = Color(0x14FFFFFF)
val GlassWhiteMedium = Color(0x22FFFFFF)
val GlassIndigo = Color(0x1A6C7CFF)
val GlassRed = Color(0x1AFF3B30)
val GlassGreen = Color(0x1A3DDC84)
val GlassAmber = Color(0x1AFFB020)

// ── Light Theme Surface Colors ──────────────
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightSurfaceElevated = Color(0xFFE2E8F0)
val LightOnBackground = Color(0xFF0F172A)
val LightOnSurface = Color(0xFF1E293B)
val LightOnSurfaceVariant = Color(0xFF475569)
val LightOutline = Color(0xFFCBD5E1)
val LightOutlineVariant = Color(0xFFE2E8F0)
val LightInverseSurface = Color(0xFF334155)
val LightInverseOnSurface = Color(0xFFF1F5F9)
val LightInversePrimary = Color(0xFFA5B4FC)