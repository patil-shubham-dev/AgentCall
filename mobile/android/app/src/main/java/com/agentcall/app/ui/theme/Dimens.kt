package com.agentcall.app.ui.theme

import androidx.compose.ui.unit.dp

// 4dp base — exact from approved HTML (tmp/agentcall-design/index.html)
object Spacing {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 20.dp
    val XXL = 24.dp
    val XXXL = 32.dp

    val ScreenPadding = XL   // 20 horizontal (HTML Screen 20)
    val CardPadding = L      // 16 inner (HTML Card 16)
    val SectionGap = XXL     // 24 between sections (HTML Section 24/10)
    val SectionLabelGap = 10.dp // 10 below label (HTML)
    val GridGap = M          // 12 between items (HTML Grid 12)
    val ListGap = 10.dp      // 10 between list rows (HTML List 10)
}
