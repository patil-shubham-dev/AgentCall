package com.agentcall.app.ui.theme

import androidx.compose.ui.unit.dp

// Semantic spacing scale (4dp base), proportions stepping ~×1.5 (φ-informed
// heuristic — relationships, not literal 1.618 multipliers):
//   8  tight   — inside a relationship (icon→text, avatar→label, label→its card)
//   12 normal  — adjacent content (card inner gaps, list items, heading→body)
//   16 edge    — card interiors (breathing room, Material baseline)
//   20 outer   — screen margins & major section boundaries (comfortable edge air)
//   24 tail    — scrollable list bottom breathing
// System WindowInsets are NOT part of this scale — applied separately, once.
object Spacing {
    // Tight relationships
    val M = 8.dp

    // Normal adjacent content
    val XS = 12.dp
    val S = 12.dp
    val L = 12.dp
    val XL = 12.dp

    // Major separation
    val Section = 20.dp
    val XXL = 24.dp      // scrollable list bottom tail
    val XXXL = 20.dp     // empty-state vertical air

    // Edges & surfaces
    val ScreenPadding = 20.dp    // left/right outer content margin on every screen
    val CardPadding = 16.dp      // interior padding of cards/plates
    val SectionGap = 20.dp       // above a section label (separates groups)
    val SectionLabelGap = 8.dp   // section label → its content (belongs together)
    val GridGap = 12.dp
    val ListGap = 12.dp
}
