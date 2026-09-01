package com.agentcall.app.ui.theme

import androidx.compose.ui.unit.dp

// Minimal premium: tokens exactly as approved HTML (tmp/agentcall-design/index.html :root)
object Radii {
    val Pill = 999.dp       // full pill
    val Card = 12.dp        // cards (HTML --radius-card)
    val Panel = 16.dp       // panels/context cards (HTML --radius-panel)
    val Field = 12.dp       // inputs / buttons / banners (HTML --radius-field)
    val Sheet = 20.dp       // sheets (HTML --radius-sheet)

    // Compat aliases
    val Plate = Card
    val Control = Field
}
