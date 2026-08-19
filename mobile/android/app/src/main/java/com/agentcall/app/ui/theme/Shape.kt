package com.agentcall.app.ui.theme

import androidx.compose.ui.unit.dp

// ── Corner radius scale ──────────────────────
// R0 pills/nav keys · R1 plates · R2 fields/buttons/banners ·
// R3 panels/context cards · R4 sheets · Full circles for actions/avatars.
// Concentric rule: an inner element is never more rounded than its container.

object Radii {
    val Pill = 4.dp
    val Plate = 8.dp
    val Control = 12.dp
    val Panel = 16.dp
    val Sheet = 20.dp
}
