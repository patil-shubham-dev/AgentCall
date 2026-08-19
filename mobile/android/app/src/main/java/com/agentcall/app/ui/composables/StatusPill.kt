package com.agentcall.app.ui.composables

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentcall.app.ui.theme.LampOff
import com.agentcall.app.ui.theme.Radii
import com.agentcall.app.ui.theme.Slate700
import com.agentcall.app.ui.theme.Slate800

/**
 * The standard status pill: a lamp (lit = colored, pulsing when live; unlit
 * = idle) plus a label. One component everywhere — Home header, Settings
 * connection row, empty states — so status always reads the same way.
 */
@Composable
fun StatusPill(
    label: String,
    lampColor: Color,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusPill")
    val pulseAlpha by infiniteTransition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "lamp",
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radii.Pill),
        color = Slate800,
        border = BorderStroke(1.dp, Slate700),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (pulse) {
                            lampColor.copy(alpha = 0.5f + pulseAlpha * 0.5f)
                        } else {
                            if (lampColor == LampOff) LampOff else lampColor
                        }
                    ),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (lampColor == LampOff) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    lampColor
                },
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
