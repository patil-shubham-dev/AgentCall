package com.agentcall.app.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.agentcall.app.R
import com.agentcall.app.ui.theme.AgentCallTheme
import com.agentcall.app.ui.theme.IncomingCallTokens
import com.agentcall.app.ui.theme.IncomingCallTokens.Dimen
import com.agentcall.app.ui.theme.IncomingCallTokens.Motion
import com.agentcall.app.ui.theme.IncomingCallTokens.Palette
import com.agentcall.app.ui.theme.IncomingCallTokens.Type
import kotlinx.coroutines.delay

/**
 * Incoming-call surface.
 *
 * One centred identity block (mark, agent name, caption, reason) in the space
 * above a pinned action area (Remind me, 20dp, slide control). The auto-decline
 * deadline is a clock in the top-right margin, on the same 24dp grid as the
 * rest of the screen. The identity area is the only scrollable region; it
 * scrolls only when large type makes it taller than the space left by the
 * actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingCallScreen(
    callerName: String,
    contextSummary: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onLater: (Int) -> Unit,
    clientInfoName: String? = null,
    initialSecondsLeft: Int = Motion.DefaultTimeoutSeconds,
) {
    var showLaterPicker by remember { mutableStateOf(false) }
    var showReasonSheet by remember { mutableStateOf(false) }
    var resolved by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(initialSecondsLeft) }

    // Auto-decline countdown; paused while the Later picker is open so choosing
    // a callback cannot race the timer. The reason sheet does not pause it.
    LaunchedEffect(showLaterPicker, resolved) {
        if (showLaterPicker || resolved) return@LaunchedEffect
        while (secondsLeft > 0 && !resolved) {
            delay(1_000)
            secondsLeft--
        }
        if (!resolved) {
            resolved = true
            onDecline()
        }
    }

    val agentName = clientInfoName?.takeIf { it.isNotBlank() }
        ?: callerName.ifBlank { "Agent" }
    val reason = remember(contextSummary) { contextSummary.trim() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .drawBehind {
                // Soft violet halo behind the identity block. It is the only
                // ambient light on the surface — no other violet recurrence.
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Palette.Glow.copy(alpha = 0.13f),
                            0.48f to Palette.Glow.copy(alpha = 0.035f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.46f),
                        radius = size.width * 1.05f,
                    ),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Dimen.ScreenPadding),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(bottom = Dimen.ContentBottomBias),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CallerIdentity(agentName = agentName)
                        if (reason.isNotBlank()) {
                            Spacer(modifier = Modifier.height(Dimen.CaptionToReason))
                            ReasonText(
                                reason = reason,
                                onShowFull = { showReasonSheet = true },
                            )
                        }
                    }
                }
            }

            RemindMeButton(
                onClick = { if (!resolved) showLaterPicker = true },
                enabled = !resolved,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(Dimen.RemindToSliderGap))

            SlideToAnswer(
                onAnswer = {
                    if (!resolved) {
                        resolved = true
                        onAnswer()
                    }
                },
                onDecline = {
                    if (!resolved) {
                        resolved = true
                        onDecline()
                    }
                },
                enabled = !resolved,
            )

            Spacer(modifier = Modifier.height(Dimen.BottomGap))
        }

        CallClock(
            secondsLeft = secondsLeft,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 24.dp, end = Dimen.ScreenPadding),
        )

        if (showReasonSheet) {
            ModalBottomSheet(
                onDismissRequest = { showReasonSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Palette.Surface,
                contentColor = Palette.OnSurface,
                scrimColor = Palette.Scrim,
                shape = RoundedCornerShape(
                    topStart = Dimen.SheetCorner,
                    topEnd = Dimen.SheetCorner,
                ),
                dragHandle = {
                    BottomSheetDefaults.DragHandle(color = Palette.Muted.copy(alpha = 0.40f))
                },
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimen.SheetPadding)
                        .padding(bottom = Dimen.SheetBottomGap),
                ) {
                    Text(
                        text = reason,
                        style = Type.Reason,
                        color = Palette.OnSurface,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        }

        if (showLaterPicker) {
            ModalBottomSheet(
                onDismissRequest = { showLaterPicker = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Palette.Surface,
                contentColor = Palette.OnSurface,
                scrimColor = Palette.Scrim,
                shape = RoundedCornerShape(
                    topStart = Dimen.SheetCorner,
                    topEnd = Dimen.SheetCorner,
                ),
                dragHandle = {
                    BottomSheetDefaults.DragHandle(color = Palette.Muted.copy(alpha = 0.40f))
                },
                tonalElevation = 0.dp,
            ) {
                RemindMeSheet(
                    onPick = { minutes ->
                        showLaterPicker = false
                        if (!resolved) {
                            resolved = true
                            onLater(minutes)
                        }
                    },
                )
            }
        }
    }
}

/** Mark at 112dp, name, caption. The clock lives in the top-right margin. */
@Composable
fun CallerIdentity(
    agentName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            // The launcher icon's actual two-tone mark. Never tint this:
            // the violet dots and cream curves are the brand asset.
            painter = painterResource(R.drawable.agentcall_logo_transparent),
            contentDescription = null,
            modifier = Modifier.size(Dimen.LogoSize),
            tint = Color.Unspecified,
        )

        Spacer(modifier = Modifier.height(Dimen.LogoToName))

        Text(
            text = agentName,
            style = Type.CallerName,
            color = Palette.OnSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.height(Dimen.NameToCaption))

        Text(
            text = "AgentCall · AI voice call",
            style = Type.Caption,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Deadline clock: 15/20 semibold, tabular, amber in the final ten seconds. */
@Composable
private fun CallClock(
    secondsLeft: Int,
    modifier: Modifier = Modifier,
) {
    val urgent = secondsLeft in 1..Motion.UrgentSeconds
    val color by animateColorAsState(
        targetValue = if (urgent) Palette.Amber else Palette.OnSurface,
        animationSpec = tween(Motion.ColorMs),
        label = "clockColor",
    )
    Text(
        text = formatClock(secondsLeft),
        style = Type.Clock,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (secondsLeft == 1) {
                "Time remaining: 1 second"
            } else {
                "Time remaining: $secondsLeft seconds"
            }
        },
    )
}

private fun formatClock(secondsLeft: Int): String {
    val safe = secondsLeft.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}

/**
 * Centred muted reason, max three lines, ellipsised at a word boundary by
 * measuring word-prefix candidates. Tapping a truncated reason opens the full
 * text in a sheet.
 */
@Composable
fun ReasonText(
    reason: String,
    onShowFull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        val display = remember(reason, widthPx, density) {
            measureWordBoundaryEllipsis(
                measurer = measurer,
                raw = reason,
                style = Type.Reason,
                widthPx = widthPx,
                maxLines = Dimen.ReasonMaxLines,
            )
        }
        Text(
            text = display.text,
            style = Type.Reason,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            maxLines = Dimen.ReasonMaxLines,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (display.truncated) {
                        Modifier
                            .semantics { role = Role.Button }
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Show full reason",
                                onClick = onShowFull,
                            )
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/** 48dp tonal pill above the slider. Own fill, never bare text. */
@Composable
fun RemindMeButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val content = if (enabled) Palette.Muted else Palette.Muted.copy(alpha = 0.45f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = Palette.SurfaceRaised,
        contentColor = content,
        border = BorderStroke(Dimen.SliderOutline, Palette.Outline),
        tonalElevation = 0.dp,
        modifier = modifier
            .height(Dimen.RemindHeight)
            .widthIn(min = 152.dp),
    ) {
        Row(
            modifier = Modifier
                .height(Dimen.RemindHeight)
                .padding(horizontal = Dimen.RemindPaddingHorizontal),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(Dimen.RemindIcon),
            )
            Spacer(modifier = Modifier.size(Dimen.RemindIconGap))
            Text(
                text = "Remind me",
                style = Type.Control,
                color = content,
            )
        }
    }
}

@Composable
private fun RemindMeSheet(onPick: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimen.SheetPadding)
            .padding(bottom = Dimen.SheetBottomGap),
    ) {
        Text(
            text = "Remind me",
            style = Type.SheetTitle,
            color = Palette.OnSurface,
        )
        Spacer(modifier = Modifier.height(16.dp))
        listOf(5 to "5 min", 15 to "15 min", 60 to "1 hour").forEach { (minutes, label) ->
            Surface(
                onClick = { onPick(minutes) },
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                contentColor = Palette.OnSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimen.SheetOptionHeight),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimen.SheetOptionHeight)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = Type.SheetOption,
                        color = Palette.OnSurface,
                    )
                }
            }
        }
    }
}

private data class ReasonDisplay(val text: String, val truncated: Boolean)

/**
 * Compose's ellipsis can cut mid-word. Measure the full string first; if it
 * overflows, binary-search the largest word prefix that fits with an ellipsis,
 * so the cut lands on a word boundary.
 */
private fun measureWordBoundaryEllipsis(
    measurer: androidx.compose.ui.text.TextMeasurer,
    raw: String,
    style: TextStyle,
    widthPx: Int,
    maxLines: Int,
): ReasonDisplay {
    val normalized = raw.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return ReasonDisplay("", false)
    val constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1))
    val full = measurer.measure(
        text = normalized,
        style = style,
        overflow = TextOverflow.Clip,
        maxLines = maxLines,
        constraints = constraints,
    )
    if (!full.hasVisualOverflow) return ReasonDisplay(normalized, false)

    val words = normalized.split(' ')
    var low = 0
    var high = words.size - 1
    var best = ""
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = words.take(mid).joinToString(" ") + "…"
        val layout = measurer.measure(
            text = candidate,
            style = style,
            overflow = TextOverflow.Clip,
            maxLines = maxLines,
            constraints = constraints,
        )
        if (!layout.hasVisualOverflow) {
            best = candidate
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    val fallback = words.firstOrNull()?.let { first ->
        if (first.length > 1) first.dropLast(1) + "…" else "…"
    } ?: "…"
    return ReasonDisplay(best.ifEmpty { fallback }, true)
}

// ── Previews ────────────────────────────────────────────────────────────────

@Preview(
    name = "Incoming - normal",
    showBackground = true,
    backgroundColor = 0xFF0E0F12,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun PreviewIncomingNormal() {
    AgentCallTheme(darkTheme = true) {
        IncomingCallScreen(
            callerName = "ChatGPT",
            contextSummary = "Checking in on the deployment you started this morning.",
            onAnswer = {},
            onDecline = {},
            onLater = {},
            clientInfoName = "ChatGPT",
        )
    }
}

@Preview(
    name = "Incoming - long reason",
    showBackground = true,
    backgroundColor = 0xFF0E0F12,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun PreviewIncomingLongReason() {
    AgentCallTheme(darkTheme = true) {
        IncomingCallScreen(
            callerName = "ChatGPT",
            contextSummary = "Your production rollout is paused because the database migration check reported two pending steps. I can walk you through the safe order, confirm backups, and help decide whether to continue now or schedule a maintenance window.",
            onAnswer = {},
            onDecline = {},
            onLater = {},
            clientInfoName = "ChatGPT",
        )
    }
}

@Preview(
    name = "Incoming - last 10s",
    showBackground = true,
    backgroundColor = 0xFF0E0F12,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun PreviewIncomingLastTenSeconds() {
    AgentCallTheme(darkTheme = true) {
        IncomingCallScreen(
            callerName = "ChatGPT",
            contextSummary = "Checking in on the deployment you started this morning.",
            onAnswer = {},
            onDecline = {},
            onLater = {},
            clientInfoName = "ChatGPT",
            initialSecondsLeft = 7,
        )
    }
}

@Preview(
    name = "Incoming - font scale 1.3",
    showBackground = true,
    backgroundColor = 0xFF0E0F12,
    widthDp = 411,
    heightDp = 891,
    fontScale = 1.3f,
)
@Composable
private fun PreviewIncomingLargeFont() {
    AgentCallTheme(darkTheme = true) {
        IncomingCallScreen(
            callerName = "ChatGPT",
            contextSummary = "Your production rollout is paused because the database migration check reported two pending steps. I can walk you through the safe order.",
            onAnswer = {},
            onDecline = {},
            onLater = {},
            clientInfoName = "ChatGPT",
        )
    }
}

@Preview(
    name = "Incoming - 360dp",
    showBackground = true,
    backgroundColor = 0xFF0E0F12,
    widthDp = 360,
    heightDp = 640,
)
@Composable
private fun PreviewIncomingSmallScreen() {
    AgentCallTheme(darkTheme = true) {
        IncomingCallScreen(
            callerName = "ChatGPT",
            contextSummary = "Checking in on the deployment you started this morning.",
            onAnswer = {},
            onDecline = {},
            onLater = {},
            clientInfoName = "ChatGPT",
        )
    }
}
