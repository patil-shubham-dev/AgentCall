package com.agentcall.app.call

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agentcall.app.ui.theme.BrandPurple
import com.agentcall.app.ui.theme.DarkBorder
import com.agentcall.app.ui.theme.DarkSurfaceElevated
import com.agentcall.app.ui.theme.Error
import com.agentcall.app.ui.theme.OnAccent
import com.agentcall.app.ui.theme.Success
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * One gesture for both call decisions: drag right to answer, left to decline.
 *
 * Interaction contract:
 * - the thumb follows the finger 1:1 and resists past the track edge,
 * - a clear threshold (62% of the travel) must be crossed before release
 *   commits, so a brush of the control can never answer or decline,
 * - releasing below the threshold springs the thumb back and fires nothing,
 * - the track fills and the thumb turns green/red in the chosen direction,
 * - a haptic tick marks the threshold, a second marks the commit,
 * - TalkBack gets both decisions as custom actions instead of a drag.
 */
@Composable
fun SwipeCallControl(
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val thumbSize = 60.dp
    val trackHeight = 92.dp
    val thumbPx = with(density) { thumbSize.toPx() }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val travel = ((trackWidthPx - thumbPx) / 2f).coerceAtLeast(1f)
    val threshold = travel * 0.62f

    var dragPx by remember { mutableFloatStateOf(0f) }
    val settle = remember { Animatable(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var settling by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var crossedRight by remember { mutableStateOf(false) }
    var crossedLeft by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    fun springBack(value: Float) {
        settling = true
        settleJob = scope.launch {
            settle.snapTo(value)
            settle.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 460f),
            )
            dragPx = 0f
            settling = false
        }
    }

    fun release(commit: Boolean) {
        if (committed) return
        crossedRight = false
        crossedLeft = false
        val value = dragPx
        when {
            !commit -> springBack(value)
            value >= threshold -> {
                committed = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                settling = true
                settleJob = scope.launch {
                    settle.snapTo(value)
                    settle.animateTo(
                        targetValue = travel * 1.12f,
                        animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing),
                    )
                    onAnswer()
                }
            }
            value <= -threshold -> {
                committed = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                settling = true
                settleJob = scope.launch {
                    settle.snapTo(value)
                    settle.animateTo(
                        targetValue = -travel * 1.12f,
                        animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing),
                    )
                    onDecline()
                }
            }
            else -> springBack(value)
        }
    }

    val offset = if (settling) settle.value else dragPx
    val rightProgress = (offset / threshold).coerceIn(0f, 1f)
    val leftProgress = (-offset / threshold).coerceIn(0f, 1f)
    val thumbColor = when {
        offset > 1f -> Success
        offset < -1f -> Error
        else -> BrandPurple
    }
    // The thumb responds to contact, not an always-running idle loop. This
    // creates a precise, tactile affordance without competing with the caller.
    val thumbScale by animateFloatAsState(
        targetValue = when {
            committed -> 0.96f
            dragging -> 1.045f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "callThumbContact",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .onSizeChanged { trackWidthPx = it.width }
            .semantics {
                contentDescription = "Call actions. Swipe right to answer, swipe left to decline."
                customActions = listOf(
                    CustomAccessibilityAction("Answer call") {
                        if (enabled && !committed) {
                            committed = true
                            onAnswer()
                            true
                        } else {
                            false
                        }
                    },
                    CustomAccessibilityAction("Decline call") {
                        if (enabled && !committed) {
                            committed = true
                            onDecline()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
            .drawBehind {
                val value = if (settling) settle.value else dragPx
                val lineHeight = 4.dp.toPx()
                val lineY = size.height / 2f - lineHeight / 2f
                val lineStart = thumbPx / 2f
                drawRoundRect(
                    color = DarkSurfaceElevated.copy(alpha = 0.92f),
                    topLeft = Offset(lineStart, lineY),
                    size = Size(size.width - lineStart * 2f, lineHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(lineHeight / 2f),
                )
                if (value > 0.5f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(Success.copy(alpha = 0f), Success.copy(alpha = 0.56f * rightProgress)),
                        ),
                        topLeft = Offset(size.width / 2f, lineY),
                        size = Size(value, lineHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(lineHeight / 2f),
                    )
                } else if (value < -0.5f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(Error.copy(alpha = 0.56f * leftProgress), Error.copy(alpha = 0f)),
                        ),
                        topLeft = Offset(size.width / 2f + value, lineY),
                        size = Size(-value, lineHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(lineHeight / 2f),
                    )
                }
            }
            .pointerInput(enabled, threshold, travel) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        if (committed) return@detectHorizontalDragGestures
                        // Grab the thumb where it currently is, even mid-spring;
                        // cancel that spring so it can't snap the thumb back
                        // while the finger is dragging it.
                        settleJob?.cancel()
                        settleJob = null
                        crossedRight = false
                        crossedLeft = false
                        dragPx = settle.value
                        settling = false
                        dragging = true
                    },
                    onDragEnd = {
                        dragging = false
                        release(commit = true)
                    },
                    onDragCancel = {
                        dragging = false
                        release(commit = false)
                    },
                ) { change, dragAmount ->
                    if (committed) return@detectHorizontalDragGestures
                    change.consume()
                    dragPx = resist(dragPx + dragAmount, travel)
                    if (!crossedRight && dragPx >= threshold) {
                        crossedRight = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else if (crossedRight && dragPx < threshold * 0.85f) {
                        crossedRight = false
                    }
                    if (!crossedLeft && dragPx <= -threshold) {
                        crossedLeft = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else if (crossedLeft && dragPx > -threshold * 0.85f) {
                        crossedLeft = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Left affordance — decline
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 2.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Error.copy(alpha = 0.10f + 0.12f * leftProgress))
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .graphicsLayer {
                    alpha = (0.55f + 0.45f * leftProgress - 0.35f * rightProgress).coerceIn(0.18f, 1f)
                    val scale = 1f + 0.04f * leftProgress
                    scaleX = scale
                    scaleY = scale
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.CallEnd,
                contentDescription = null,
                tint = Error,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "Decline",
                style = MaterialTheme.typography.labelLarge,
                color = Error,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Right affordance — answer
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Success.copy(alpha = 0.10f + 0.12f * rightProgress))
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .graphicsLayer {
                    alpha = (0.55f + 0.45f * rightProgress - 0.35f * leftProgress).coerceIn(0.18f, 1f)
                    val scale = 1f + 0.04f * rightProgress
                    scaleX = scale
                    scaleY = scale
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Answer",
                style = MaterialTheme.typography.labelLarge,
                color = Success,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                Icons.Default.Call,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(20.dp),
            )
        }

        // Draggable handle between two discrete call targets. The minimal line
        // reads as a gesture path without framing it in a web-style card.
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor)
                .drawBehind {
                    val progress = (abs(offset) / threshold).coerceIn(0f, 1f)
                    if (progress > 0f) {
                        val inset = 2.dp.toPx()
                        drawArc(
                            color = OnAccent.copy(alpha = 0.9f),
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                .border(1.dp, OnAccent.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    offset > 1f -> Icons.Default.Call
                    offset < -1f -> Icons.Default.CallEnd
                    else -> Icons.Default.SwapHoriz
                },
                contentDescription = null,
                tint = OnAccent,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** 1:1 inside the track, then a firm rubber band past the edge. */
private fun resist(raw: Float, limit: Float): Float {
    val magnitude = abs(raw)
    if (magnitude <= limit) return raw
    val sign = if (raw < 0f) -1f else 1f
    val over = magnitude - limit
    return sign * (limit + limit * 0.24f * (1f - exp(-over / (limit * 0.55f))))
}
