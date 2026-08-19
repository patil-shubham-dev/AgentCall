package com.agentcall.app.ui.composables

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The standard circular call action (Record/Mute/Speaker/Repeat on the active
 * call, and Decline/Answer/Later on the incoming call). [size] carries the
 * hierarchy — the primary action is the biggest circle.
 */
@Composable
fun ActionCircle(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isSolid: Boolean = false,
    solidColor: Color = Color.Green,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    bgColor: Color = Color.Transparent,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f),
        label = "actionCirclePress",
    )
    val pressElevation by animateDpAsState(
        targetValue = if (isPressed) 4.dp else 8.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "actionCircleElevation",
    )

    // The whole column (circle + label) is the tap target. Taps on the circle
    // are consumed by the inner Button/Surface (it wins hit-testing on the up
    // event), so only taps on the label fall through to this clickable — both
    // fire the same onClick. Without this, the label text below the circle was
    // dead space users tapped expecting an action.
    val labelInteractionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier.clickable(
            interactionSource = labelInteractionSource,
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isSolid) {
            Button(
                onClick = onClick,
                modifier = Modifier.size(size).scale(pressScale),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = solidColor),
                contentPadding = PaddingValues(0.dp),
                interactionSource = interactionSource,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = pressElevation),
            ) {
                Icon(icon, label, modifier = Modifier.size(size * 0.44f), tint = iconTint)
            }
        } else {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = bgColor,
                tonalElevation = 0.dp,
                shadowElevation = pressElevation,
                interactionSource = interactionSource,
            ) {
                Box(modifier = Modifier.size(size).scale(pressScale), contentAlignment = Alignment.Center) {
                    Icon(icon, label, modifier = Modifier.size(size * 0.44f), tint = iconTint)
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            fontWeight = FontWeight.Medium,
        )
    }
}
