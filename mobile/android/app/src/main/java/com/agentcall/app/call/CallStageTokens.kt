package com.agentcall.app.call

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentcall.app.ui.theme.AgentCallSans

/**
 * Stage-local tokens from docs/ONGOING_CALL_STAGE_SPEC.md §3.
 *
 * The ongoing-call stage has its own visual register (near-black ground, one
 * violet for the AI, warm paper for the human). The app-wide theme is close but
 * not identical, so the stage keeps its own values instead of inheriting a
 * slightly different charcoal or surface. Do not fold these back into the
 * global palette without re-checking the measured contrasts in the spec.
 */
object CallStageColors {
    val Bg = Color(0xFF0E0F12)
    val Surface = Color(0xFF191B20)
    val SurfaceRaised = Color(0xFF202329)
    val Outline = Color.White.copy(alpha = 0.12f)
    val Hairline = Color.White.copy(alpha = 0.10f)
    val OnSurface = Color(0xFFF1F2F4)
    val Muted = Color(0xFF9CA1AB)

    val Ai = Color(0xFF7967DE)
    val AiBright = Color(0xFF9A8BF2)
    val Human = Color(0xFFEFE9DF)

    val Decline = Color(0xFFFF6B6B)
    val DeclineIcon = Color(0xFF16181C)
    val EndLabel = Color(0xFFC98282)
    val Amber = Color(0xFFE8B04B)
    val IdleDot = Color(0xFF6E737D)

    val SecondaryButton = Color(0xFF5B4BC4)
    val SecondaryButtonPressed = Color(0xFF6A5AD4)
    val SendDisabled = Color(0xFF2B2E36)
    val SendDisabledIcon = Color(0xFF6E737D)

    val Scrim = Color.Black.copy(alpha = 0.62f)
    val FieldFocus = AiBright.copy(alpha = 0.55f)

    /** Pressed/active tint for the mute disc. */
    val AiTint = Ai.copy(alpha = 0.16f)
    val AiTintBorder = AiBright.copy(alpha = 0.32f)
}

object CallStageDimens {
    val Side = 24.dp
    val ContentWidth = 312.dp
    val WaveHeight = 180.dp

    /** 2 dp of air below the top voice field. */
    val HeaderTop = 182.dp
    val HeaderHeight = 46.dp
    val MarkSize = 38.dp

    val ComposerRadius = 16.dp
    val FieldPaddingH = 14.dp
    val FieldPaddingV = 11.dp
    val FieldLineHeight = 22.dp
    val ComposerGap = 12.dp

    val ControlsHeight = 76.dp
    val ControlWidth = 72.dp
    val ControlGap = 8.dp
    val DiscSize = 56.dp

    /** 180 dp of bottom voice field + 16 dp of air. */
    val ControlsBottomAir = 196.dp

    val LiveWidth = 312.dp
    val PreviousWidth = 300.dp
    val LiveMinHeight = 118.dp
    val CaptionMinHeight = 90.dp

    val SheetTopRadius = 28.dp
    val ThreadTopRadius = 24.dp
}

object CallStageText {
    val Name = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    )
    val Presence = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    )
    val Duration = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
    val Previous = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    )
    val Speaker = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.24.sp,
    )
    val Caption = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.15).sp,
    )
    val SheetTitle = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val SheetRow = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
    )
    val SheetValue = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    )
    val RouteName = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    )
    val RouteSub = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    )
    val ControlLabel = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    )
    val Composer = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    )
    val ThreadMessage = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    )
    val ThreadMeta = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.24.sp,
    )
    val ThreadTime = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    val EndedTitle = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    )
    val EndedDuration = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )
    val ContextBody = TextStyle(
        fontFamily = AgentCallSans,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    )
}

/** The prototype's `--ease-out: cubic-bezier(0.22, 1, 0.36, 1)`. */
val StageEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

enum class StagePresence {
    CONNECTING,
    LISTENING,
    LISTENING_TO_YOU,
    THINKING,
    SPEAKING,
    MUTED,
    RECONNECTING,
    OFFLINE,
    ENDED,
}

val StagePresence.label: String
    get() = when (this) {
        StagePresence.CONNECTING -> "Connecting…"
        StagePresence.LISTENING -> "Connected"
        StagePresence.LISTENING_TO_YOU -> "Listening to you"
        StagePresence.THINKING -> "Thinking…"
        StagePresence.SPEAKING -> "AI speaking"
        StagePresence.MUTED -> "AI muted"
        StagePresence.RECONNECTING -> "Reconnecting…"
        StagePresence.OFFLINE -> "Agent offline"
        StagePresence.ENDED -> "Call ended"
    }

val StagePresence.dotColor: Color
    get() = when (this) {
        StagePresence.SPEAKING -> CallStageColors.AiBright
        StagePresence.MUTED -> CallStageColors.Amber
        StagePresence.LISTENING_TO_YOU -> CallStageColors.Human
        StagePresence.RECONNECTING -> CallStageColors.Amber
        StagePresence.THINKING -> CallStageColors.AiBright
        StagePresence.CONNECTING,
        StagePresence.LISTENING,
        StagePresence.OFFLINE,
        StagePresence.ENDED,
        -> CallStageColors.IdleDot
    }
