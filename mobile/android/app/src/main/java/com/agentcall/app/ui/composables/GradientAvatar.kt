package com.agentcall.app.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentcall.app.ui.theme.GradientBrandEnd
import com.agentcall.app.ui.theme.GradientBrandStart
import com.agentcall.app.ui.theme.Slate50

@Composable
fun GradientAvatar(
    size: Dp = 56.dp,
    gradient: List<Color> = listOf(GradientBrandStart, GradientBrandEnd),
    icon: ImageVector = Icons.Default.SmartToy,
    contentDescription: String = "AI avatar",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f * 0.85f
            drawCircle(Color.White.copy(alpha = 0.08f), r)
        }
        Icon(icon, contentDescription, modifier = Modifier.size(size * 0.45f), tint = Slate50)
    }
}
