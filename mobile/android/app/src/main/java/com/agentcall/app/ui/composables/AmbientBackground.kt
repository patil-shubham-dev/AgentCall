package com.agentcall.app.ui.composables

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.agentcall.app.ui.theme.*
import kotlin.math.sin

/**
 * A shared animated ambient background that renders drifting gradient orbs.
 * Used as a low-opacity backdrop across all screens for visual consistency.
 */
@Composable
fun AmbientBackground(
    modifier: Modifier = Modifier,
    accentColor: Color = Indigo500,
    secondaryColor: Color = GradientBrandEnd,
    speedMultiplier: Float = 1f,
    density: Float = 1f,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambientBg")

    val driftX1 by infiniteTransition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween((8000f / speedMultiplier).toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "driftX1"
    )
    val driftX2 by infiniteTransition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween((11000f / speedMultiplier).toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "driftX2"
    )
    val pulse1 by infiniteTransition.animateFloat(
        0.4f, 0.8f,
        infiniteRepeatable(tween((4000f / speedMultiplier).toInt(), easing = EaseInOutSine), RepeatMode.Reverse),
        label = "pulse1"
    )
    val pulse2 by infiniteTransition.animateFloat(
        0.3f, 0.7f,
        infiniteRepeatable(tween((5000f / speedMultiplier).toInt(), easing = EaseInOutSine, delayMillis = 1000), RepeatMode.Reverse),
        label = "pulse2"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Orb 1 — top-right drift
        val ox1 = w * 0.7f + sin(driftX1 * 2f * kotlin.math.PI.toFloat()) * w * 0.2f
        val oy1 = h * 0.2f + sin(driftX1 * 2f * kotlin.math.PI.toFloat() * 0.7f) * h * 0.12f
        drawCircle(
            color = accentColor.copy(alpha = 0.06f * pulse1 * density),
            radius = w * 0.45f,
            center = Offset(ox1, oy1),
        )

        // Orb 2 — bottom-left drift
        val ox2 = w * 0.25f + sin(driftX2 * 2f * kotlin.math.PI.toFloat() + 1.5f) * w * 0.18f
        val oy2 = h * 0.65f + sin(driftX2 * 2f * kotlin.math.PI.toFloat() * 0.6f + 1f) * h * 0.1f
        drawCircle(
            color = secondaryColor.copy(alpha = 0.05f * pulse2 * density),
            radius = w * 0.5f,
            center = Offset(ox2, oy2),
        )

        // Orb 3 — center subtle (if density is high)
        if (density > 0.5f) {
            val ox3 = w * 0.5f + sin(driftX1 * 2f * kotlin.math.PI.toFloat() * 0.4f + 3f) * w * 0.1f
            val oy3 = h * 0.4f + sin(driftX2 * 2f * kotlin.math.PI.toFloat() * 0.5f + 2f) * h * 0.08f
            drawCircle(
                color = accentColor.copy(alpha = 0.03f * pulse1 * density),
                radius = w * 0.35f,
                center = Offset(ox3, oy3),
            )
        }
    }
}
