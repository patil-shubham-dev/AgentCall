package com.agentcall.app.call

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.agentcall.app.ui.theme.IncomingCallTokens.Dimen
import com.agentcall.app.ui.theme.IncomingCallTokens.Motion
import com.agentcall.app.ui.theme.IncomingCallTokens.Palette
import com.agentcall.app.ui.theme.IncomingCallTokens.Type
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlin.math.abs
import kotlin.math.min

private enum class SlideAnchor { Decline, Center, Answer }

/**
 * Bidirectional slide control: left declines, right answers.
 *
 * Three anchors (Decline / Center / Answer) driven by [AnchoredDraggableState].
 * Release past 75% travel, or a fling over the velocity threshold, commits once
 * and disables input.
 *
 * Everything that follows the finger — thumb position, thumb tint, icon turn,
 * fill extent and the charging ring — is read from `state.offset` inside
 * draw/graphics-layer lambdas, so a drag repaints without recomposing the
 * slider. Only the two haptic thresholds recompose.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlideToAnswer(
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val thumbSize = Dimen.SliderThumb
    val thumbPx = with(density) { thumbSize.toPx() }
    val insetPx = with(density) { Dimen.SliderThumbInset.toPx() }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val travel = ((trackWidthPx - thumbPx) / 2f - insetPx).coerceAtLeast(1f)

    var committed by remember { mutableStateOf(false) }
    val latestAnswer by rememberUpdatedState(onAnswer)
    val latestDecline by rememberUpdatedState(onDecline)

    val state = remember(density) {
        AnchoredDraggableState(
            initialValue = SlideAnchor.Center,
            positionalThreshold = { distance -> distance * Motion.SliderPositionalThreshold },
            velocityThreshold = {
                Motion.SliderVelocityDpPerSecond * density.density
            },
            animationSpec = spring(
                dampingRatio = Motion.SliderDamping,
                stiffness = Spring.StiffnessMediumLow,
            ),
            confirmValueChange = { target ->
                if (target != SlideAnchor.Center && !committed) {
                    committed = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (target == SlideAnchor.Answer) latestAnswer() else latestDecline()
                }
                true
            },
        )
    }

    LaunchedEffect(trackWidthPx, travel) {
        state.updateAnchors(
            DraggableAnchors {
                SlideAnchor.Decline at -travel
                SlideAnchor.Center at 0f
                SlideAnchor.Answer at travel
            },
        )
    }

    // The fill chases the thumb with a 70 ms linear lag, mirroring the CSS
    // trail. The value is read only inside the draw lambda.
    val fillOffset = remember { Animatable(0f) }
    LaunchedEffect(state, travel, reducedMotion) {
        snapshotFlow { state.offset }
            .filter { it.isFinite() }
            .collectLatest { target ->
                if (reducedMotion) {
                    fillOffset.snapTo(target)
                } else {
                    fillOffset.animateTo(
                        targetValue = target,
                        animationSpec = tween(
                            durationMillis = Motion.SliderFillTrailMs,
                            easing = LinearEasing,
                        ),
                    )
                }
            }
    }

    /** −1 at the decline end, 0 at rest, +1 at the answer end. */
    fun fractionOf(offset: Float): Float =
        if (offset.isFinite() && travel > 1f) (offset / travel).coerceIn(-1f, 1f) else 0f

    val active = enabled && !committed

    // Threshold tick. Hysteresis re-arms only after retreating to 65%.
    LaunchedEffect(state, travel, active) {
        if (!active || travel <= 1f) return@LaunchedEffect
        var inRight = false
        var inLeft = false
        snapshotFlow { state.offset }
            .filter { it.isFinite() }
            .collect { offset ->
                val fraction = fractionOf(offset)
                if (!inRight && fraction >= Motion.SliderPositionalThreshold) {
                    inRight = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } else if (inRight && fraction < Motion.SliderPositionalThreshold - 0.10f) {
                    inRight = false
                }
                if (!inLeft && fraction <= -Motion.SliderPositionalThreshold) {
                    inLeft = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } else if (inLeft && fraction > -(Motion.SliderPositionalThreshold - 0.10f)) {
                    inLeft = false
                }
            }
    }

    val hint = if (reducedMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "slideHint")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.ChevronMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "slideHintTravel",
        )
        value
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimen.SliderHeight)
            .onSizeChanged { trackWidthPx = it.width }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Slide to answer or decline"
                customActions = listOf(
                    CustomAccessibilityAction("Answer call") {
                        if (active) {
                            committed = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            latestAnswer()
                            true
                        } else {
                            false
                        }
                    },
                    CustomAccessibilityAction("Decline call") {
                        if (active) {
                            committed = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            latestDecline()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
            .then(
                if (active) {
                    Modifier.anchoredDraggable(state, Orientation.Horizontal)
                } else {
                    Modifier
                },
            )
            .drawBehind {
                drawSliderSurface(
                    state = state,
                    travel = travel,
                    fill = fillOffset.value,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Calm direction hint at rest. Disabled by reduce-motion and hidden once
        // the thumb leaves the centre.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val proximity = if (!state.offset.isFinite()) {
                        1f
                    } else {
                        (1f - abs(state.offset) / (travel * 0.12f)).coerceIn(0f, 1f)
                    }
                    // Resting alpha stays at 12-20%, well below the labels' 72%,
                    // so the chevrons read as a thumb hint rather than glyphs.
                    val hintAlpha = Motion.ChevronAlphaMin +
                        (Motion.ChevronAlphaMax - Motion.ChevronAlphaMin) * hint
                    alpha = if (reducedMotion) 0f else proximity * hintAlpha
                },
        ) {
            val cy = size.height / 2f
            val base = Dimen.ChevronOffset.toPx() + hint * Motion.ChevronTravelDp.dp.toPx()
            val w = Dimen.ChevronSize.toPx() * 0.45f
            val h = Dimen.ChevronSize.toPx() * 0.75f
            val stroke = Stroke(
                width = 1.25.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            drawChevron(
                cx = size.width / 2f - base,
                cy = cy,
                width = w,
                height = h,
                pointsLeft = true,
                color = Palette.OnSurface,
                stroke = stroke,
            )
            drawChevron(
                cx = size.width / 2f + base,
                cy = cy,
                width = w,
                height = h,
                pointsLeft = false,
                color = Palette.OnSurface,
                stroke = stroke,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = Dimen.SliderLabelPadding)
                .graphicsLayer {
                    alpha = labelAlpha(state.offset, travel, left = true)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Decline",
                style = Type.Control,
                color = Palette.SliderLabel,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = Dimen.SliderLabelPadding)
                .graphicsLayer {
                    alpha = labelAlpha(state.offset, travel, left = false)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Answer",
                style = Type.Control,
                color = Palette.SliderLabel,
            )
        }

        Box(
            modifier = Modifier
                .graphicsLayer {
                    val offset = state.offset
                    translationX = if (offset.isFinite()) offset else 0f
                }
                .size(thumbSize)
                .clip(CircleShape)
                .drawBehind {
                    // The tint interpolates over the whole travel instead of
                    // snapping at a threshold, so the drag reads as tinting the
                    // button rather than flipping it.
                    val fraction = fractionOf(state.offset)
                    val target = if (fraction >= 0f) Palette.Answer else Palette.Decline
                    drawCircle(color = lerp(Palette.Thumb, target, abs(fraction)))
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Call,
                contentDescription = null,
                tint = Palette.ThumbIcon,
                modifier = Modifier
                    .size(Dimen.SliderThumbIcon)
                    .graphicsLayer {
                        // The handset turns with the decline drag and is fully
                        // downward by the time the thumb reaches that end.
                        val fraction = fractionOf(state.offset)
                        rotationZ = if (fraction < 0f) {
                            Motion.SliderMaxIconRotation * -fraction
                        } else {
                            0f
                        }
                    },
            )
        }
    }
}

/**
 * Track, fill, and the two concentric rings.
 *
 * Ring structure, inside out: a 1dp cream hairline on the track edge, a 3dp
 * band, then a 1dp ring 3dp outside the track. The hairline and the band charge
 * to mint/red with the drag; the outer ring only picks up a faint tint so the
 * inner border stays the focal point.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun DrawScope.drawSliderSurface(
    state: androidx.compose.foundation.gestures.AnchoredDraggableState<SlideAnchor>,
    travel: Float,
    fill: Float,
) {
    val w = size.width
    val h = size.height
    val radius = h / 2f
    val ringWidth = Dimen.SliderRingWidth.toPx()
    val bandWidth = Dimen.SliderRingBand.toPx()
    val ringOffset = Dimen.SliderRingOffset.toPx()
    val glowWidth = Dimen.SliderRingGlow.toPx()

    val offset = state.offset
    val fraction = if (offset.isFinite() && travel > 1f) {
        (offset / travel).coerceIn(-1f, 1f)
    } else {
        0f
    }
    val charge = abs(fraction)
    val tint = if (fraction >= 0f) Palette.Answer else Palette.Decline

    drawRoundRect(
        color = Palette.Track,
        size = Size(w, h),
        cornerRadius = CornerRadius(radius),
    )

    // Fill: only the outer end is rounded; the centre edge stays square.
    if (fill.isFinite() && abs(fill) > 0.5f) {
        val centerX = w / 2f
        val magnitude = abs(fill).coerceAtMost(centerX)
        val endRadius = CornerRadius(min(radius, magnitude))
        val toAnswer = fill > 0f
        val roundRect = if (toAnswer) {
            RoundRect(
                left = centerX,
                top = 0f,
                right = w,
                bottom = h,
                topLeftCornerRadius = CornerRadius.Zero,
                topRightCornerRadius = endRadius,
                bottomRightCornerRadius = endRadius,
                bottomLeftCornerRadius = CornerRadius.Zero,
            )
        } else {
            RoundRect(
                left = centerX - magnitude,
                top = 0f,
                right = centerX,
                bottom = h,
                topLeftCornerRadius = endRadius,
                topRightCornerRadius = CornerRadius.Zero,
                bottomRightCornerRadius = CornerRadius.Zero,
                bottomLeftCornerRadius = endRadius,
            )
        }
        val brush = if (toAnswer) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Palette.AnswerFill.copy(alpha = 0f),
                    1f to Palette.AnswerFill,
                ),
                startX = centerX,
                endX = w,
            )
        } else {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Palette.DeclineFill,
                    1f to Palette.DeclineFill.copy(alpha = 0f),
                ),
                startX = centerX - magnitude,
                endX = centerX,
            )
        }
        drawPath(
            path = Path().apply { addRoundRect(roundRect) },
            brush = brush,
        )
    }

    val trackPath = Path().apply {
        addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = w,
                bottom = h,
                topLeftCornerRadius = CornerRadius(radius),
                topRightCornerRadius = CornerRadius(radius),
                bottomRightCornerRadius = CornerRadius(radius),
                bottomLeftCornerRadius = CornerRadius(radius),
            ),
        )
    }

    if (charge > 0.01f) {
        // Inset glow, clipped to the track so it never bleeds outside.
        clipPath(trackPath) {
            drawRoundRect(
                color = tint.copy(alpha = Motion.SliderRingGlowAlpha * charge),
                topLeft = Offset(glowWidth / 2f, glowWidth / 2f),
                size = Size(w - glowWidth, h - glowWidth),
                cornerRadius = CornerRadius((radius - glowWidth / 2f).coerceAtLeast(0f)),
                style = Stroke(width = glowWidth),
            )
        }
        // 3dp band sitting just inside the hairline.
        val bandInset = ringWidth + bandWidth / 2f
        drawRoundRect(
            color = tint.copy(alpha = Motion.SliderRingTintAlpha * charge),
            topLeft = Offset(bandInset, bandInset),
            size = Size(w - bandInset * 2f, h - bandInset * 2f),
            cornerRadius = CornerRadius((radius - bandInset).coerceAtLeast(0f)),
            style = Stroke(width = bandWidth),
        )
    }

    // Inner hairline.
    drawRoundRect(
        color = lerp(Palette.Outline, tint.copy(alpha = 0.95f), charge),
        topLeft = Offset(ringWidth / 2f, ringWidth / 2f),
        size = Size(w - ringWidth, h - ringWidth),
        cornerRadius = CornerRadius(radius - ringWidth / 2f),
        style = Stroke(width = ringWidth),
    )

    // Outer ring, 3dp clear of the track.
    val outerInset = ringOffset + ringWidth / 2f
    drawRoundRect(
        color = lerp(
            Palette.OuterRing,
            tint.copy(alpha = Motion.SliderOuterRingAlpha),
            charge,
        ),
        topLeft = Offset(-outerInset, -outerInset),
        size = Size(w + outerInset * 2f, h + outerInset * 2f),
        cornerRadius = CornerRadius(radius + outerInset),
        style = Stroke(width = ringWidth),
    )
}

/**
 * Target label holds until 74% travel, then fades to zero at 100% so it can
 * never peek out from under a landed thumb. The opposite label clears by 30%.
 */
private fun labelAlpha(offset: Float, travel: Float, left: Boolean): Float {
    if (!offset.isFinite() || travel <= 1f) return 1f
    val fraction = (offset / travel).coerceIn(-1f, 1f)
    val toward = if (left) -fraction else fraction
    return if (toward <= 0f) {
        (1f + toward / Motion.SliderLabelClearFraction).coerceIn(0f, 1f)
    } else {
        (
            1f - (toward - Motion.SliderLabelHoldFraction) /
                (1f - Motion.SliderLabelHoldFraction)
            ).coerceIn(0f, 1f)
    }
}

private fun DrawScope.drawChevron(
    cx: Float,
    cy: Float,
    width: Float,
    height: Float,
    pointsLeft: Boolean,
    color: Color,
    stroke: Stroke,
) {
    val direction = if (pointsLeft) -1f else 1f
    val path = Path().apply {
        moveTo(cx - direction * width / 2f, cy - height / 2f)
        lineTo(cx + direction * width / 2f, cy)
        lineTo(cx - direction * width / 2f, cy + height / 2f)
    }
    drawPath(path = path, color = color, style = stroke)
}

/** Compatibility wrapper kept so any existing references still compile. */
@Composable
fun SwipeCallControl(
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SlideToAnswer(
        onAnswer = onAnswer,
        onDecline = onDecline,
        modifier = modifier,
        enabled = enabled,
    )
}
