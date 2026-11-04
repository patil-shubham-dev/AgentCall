package com.agentcall.app.ui.composables

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentcall.app.R
import com.agentcall.app.ui.theme.BrandPurple

/**
 * AgentCall identity disc. The file name is historical; every call surface now
 * wears the brand mark instead of a generic robot glyph, so the call is
 * unmistakably AgentCall at a glance.
 */
@Composable
fun AgentCallAvatar(
    size: Dp = 56.dp,
    contentDescription: String? = "AgentCall AI avatar",
    modifier: Modifier = Modifier,
    discColor: Color = BrandPurple,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(discColor),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_agentcall_mark_light),
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}
