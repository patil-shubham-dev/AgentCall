package com.agentcall.app.call

/**
 * Battery-friendly adaptive poll cadence for the WS-down fallback ring poll.
 *
 * Extracted from [SignalingForegroundService] so the state-transition logic
 * is independently testable without Android framework dependencies.
 *
 * Cadence tiers (battery → latency tradeoff):
 * - **Active**: 10 s — foreground, ring in flight, or within [ACTIVE_WINDOW_MS]
 *   of the last user activity. The user is looking at the phone; latency must
 *   feel instant.
 * - **Background**: 60 s — recent background, streak ≤ [MAX_BACKGROUND_STREAK].
 *   The app just went to the background; don't punish a brief context switch.
 * - **Idle**: 5 min — deep background, streak > [MAX_BACKGROUND_STREAK].
 *   Steady-state idle; a 5-min ring latency is acceptable for a fallback.
 * - **Doze**: 5 min — OS-level deep idle; network is throttled anyway, so the
 *   slowest cadence costs nothing extra.
 */
object FallbackPollCadence {

    // ── Constants (must match SignalingForegroundService companion) ────────
    const val ACTIVE_MS = 10_000L
    const val BACKGROUND_MS = 60_000L
    const val IDLE_MS = 300_000L
    const val ACTIVE_WINDOW_MS = 5 * 60_000L
    const val DOZE_SKIP_MS = 300_000L
    const val DOZE_EVERY = 3
    const val MAX_BACKGROUND_STREAK = 20

    /**
     * Pure gate for whether the fallback poll loop should be running
     * (battery audit M4). The poll exists for one job: find rings while the
     * websocket cannot. It must therefore run ONLY when the socket is
     * genuinely down AND someone is around to act on what it finds:
     *
     * - parked sockets have no live consumer — a parked FGS relies on FCM;
     * - CONNECTING/RECONNECTING are transient — polling during them races
     *   the socket that is about to deliver the same events;
     * - with no foreground app and no ring in flight there is no surface to
     *   ring on and no user watching — idle-park stops the FGS instead.
     *
     * Booleans instead of the ConnectionState enum keep this object pure
     * Kotlin, mirroring [computeDelayMs]'s testability contract.
     */
    fun shouldRunFallbackPoll(
        isDisconnected: Boolean,
        isParked: Boolean,
        isForeground: Boolean,
        hasActiveRing: Boolean,
    ): Boolean {
        if (isParked) return false
        if (!isDisconnected) return false
        return isForeground || hasActiveRing
    }

    /**
     * Pure-function poll delay calculator.  Call from
     * [SignalingForegroundService.nextPollDelayMs] with live values; call from
     * tests with controlled values.
     *
     * @param isDeviceIdle  `PowerManager.isDeviceIdleMode`
     * @param isForeground  `ForegroundTracker.isForeground`
     * @param hasActiveRing `ringingCallId != null`
     * @param lastActivityMs  epoch-ms of the last [noteActivity] call
     * @param idlePollStreak  consecutive idle/background ticks since last activity reset
     * @param nowMs  current epoch-ms (injectable for deterministic tests)
     * @return delay in ms before the next poll tick
     */
    fun computeDelayMs(
        isDeviceIdle: Boolean,
        isForeground: Boolean,
        hasActiveRing: Boolean,
        lastActivityMs: Long,
        idlePollStreak: Int,
        nowMs: Long,
    ): Long {
        if (isDeviceIdle) {
            return if ((idlePollStreak + 1) % DOZE_EVERY == 0) IDLE_MS else DOZE_SKIP_MS
        }
        if (isForeground || hasActiveRing) {
            return ACTIVE_MS
        }
        val idleForMs = nowMs - lastActivityMs
        return when {
            idleForMs < ACTIVE_WINDOW_MS -> ACTIVE_MS
            idlePollStreak <= MAX_BACKGROUND_STREAK -> BACKGROUND_MS
            else -> IDLE_MS
        }
    }
}
