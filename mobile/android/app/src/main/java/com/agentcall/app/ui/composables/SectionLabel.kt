package com.agentcall.app.ui.composables

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Uppercase section label used above every grouped block of settings or
 * content (e.g. "SERVER CONNECTION", "YOUR AI AGENTS"). Deliberately SMALLER
 * than the page title (displaySmall): labelLarge keeps section headers clearly
 * subordinate — organizational labels, not competing headings.
 */
@Composable
fun SectionLabel(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = modifier,
    )
}
