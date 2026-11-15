package com.agentcall.app.call

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Pure geometry, copy and state-mapping rules from
 * docs/ONGOING_CALL_STAGE_SPEC.md §4, §7 and §9.
 *
 * Kept out of the composables so the parts that are easy to get subtly wrong
 * (field heights, duration copy, presence precedence, sweep speed) are unit
 * testable without an emulator.
 */
object StageMath {

    const val MAX_DRAFT_CHARS = 400
    const val MAX_COMPOSER_LINES = 4

    /** Height of the text area at 1..4 lines: 22 / 44 / 66 / 88. */
    val LineHeight = 22.dp

    /** Top + bottom padding on the field, outside the scrollport. */
    val FieldPaddingV = 11.dp
    val FieldPaddingH = 14.dp

    /** 44 / 66 / 88 / 110 for 1 / 2 / 3 / 4+ lines. */
    fun composerFieldHeight(lines: Int): Dp {
        val clamped = lines.coerceIn(1, MAX_COMPOSER_LINES)
        return (44 + 22 * (clamped - 1)).dp
    }

    /** The scrollport inside the field: 22 / 44 / 66 / 88. */
    fun composerScrollportHeight(lines: Int): Dp {
        val clamped = lines.coerceIn(1, MAX_COMPOSER_LINES)
        return (22 * clamped).dp
    }

    /**
     * Height the text field itself needs for every measured line. Beyond four
     * lines this is taller than the field: the field clips and scrolls.
     */
    fun composerContentHeight(lines: Int): Dp = (22 * max(1, lines)).dp

    /** Hard breaks are a lower bound even before the text lays out. */
    fun explicitLineCount(text: String): Int = text.count { it == '\n' } + 1

    fun visualLineCount(text: String, measuredLines: Int): Int =
        max(explicitLineCount(text), measuredLines).coerceAtLeast(1)

    /** Trim, clamp to the 400-char cap, and reject whitespace-only drafts. */
    fun deliverableDraft(raw: String): String? =
        raw.trim().take(MAX_DRAFT_CHARS).takeIf { it.isNotEmpty() }

    /** `m:ss`, the stage's timer and ended-duration copy. */
    fun formatDuration(totalSeconds: Int): String {
        val safe = totalSeconds.coerceAtLeast(0)
        val minutes = safe / 60
        val seconds = safe % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    /**
     * The ended duration as spoken copy for assistive tech: "1:24" would be
     * read as "one colon twenty-four", so the units are spelled out.
     */
    fun spokenDuration(totalSeconds: Int): String {
        val safe = totalSeconds.coerceAtLeast(0)
        val minutes = safe / 60
        val seconds = safe % 60
        return when {
            minutes == 0 -> plural(seconds, "second")
            seconds == 0 -> plural(minutes, "minute")
            else -> "${plural(minutes, "minute")} ${plural(seconds, "second")}"
        }
    }

    private fun plural(count: Int, unit: String): String =
        "$count $unit${if (count == 1) "" else "s"}"

    /**
     * Sweep distance for one frame. The dissolve runs at 480 dp/s, so the step
     * is time-based rather than a fixed per-frame amount, and a backgrounded
     * app cannot jump the front across the whole field.
     */
    fun sweepStep(deltaMs: Float): Float =
        8f * (deltaMs.coerceIn(0f, 48f) / 16.67f)

    /** Per-frame grain shrink; the RNG value is in [0, 1). */
    fun grainShrink(random: Float): Float = 0.045f + random * 0.045f

    /**
     * Presence precedence from the spec's table, extended with the app's real
     * reconnect/offline inputs. Muted only wins while the AI is actually
     * speaking — a muted AI must not hide "Listening to you".
     */
    fun stagePresence(
        phase: CallPhase,
        isAiSpeaking: Boolean,
        isProcessing: Boolean,
        aiResponding: Boolean?,
        aiVoiceMuted: Boolean,
        agentOnline: Boolean,
        userAudioLevel: Float,
    ): StagePresence = when {
        phase == CallPhase.ENDED -> StagePresence.ENDED
        phase == CallPhase.RECONNECTING -> StagePresence.RECONNECTING
        isAiSpeaking && aiVoiceMuted -> StagePresence.MUTED
        isAiSpeaking -> StagePresence.SPEAKING
        isProcessing || aiResponding == true -> StagePresence.THINKING
        phase == CallPhase.CONNECTING || phase == CallPhase.RINGING -> StagePresence.CONNECTING
        !agentOnline -> StagePresence.OFFLINE
        userAudioLevel > HUMAN_SPEECH_LEVEL -> StagePresence.LISTENING_TO_YOU
        else -> StagePresence.LISTENING
    }

    /** The short value shown on the More sheet's Audio output row. */
    fun routeShortLabel(kind: CallAudioManager.RouteKind): String = when (kind) {
        CallAudioManager.RouteKind.EARPIECE -> "Earpiece"
        CallAudioManager.RouteKind.SPEAKER -> "Speaker"
        CallAudioManager.RouteKind.BLUETOOTH -> "Bluetooth"
        CallAudioManager.RouteKind.WIRED -> "Wired"
    }

    /** The second line of an Audio sheet row. */
    fun routeSubtitle(kind: CallAudioManager.RouteKind): String = when (kind) {
        CallAudioManager.RouteKind.EARPIECE -> "Private"
        CallAudioManager.RouteKind.SPEAKER -> "Hands-free"
        CallAudioManager.RouteKind.BLUETOOTH -> "Bluetooth · Connected"
        CallAudioManager.RouteKind.WIRED -> "Wired · Connected"
    }

    /** Gate for "Listening to you"; below this the mic is just room tone. */
    const val HUMAN_SPEECH_LEVEL = 0.05f
}
