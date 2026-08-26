package com.agentcall.app.home

/**
 * Battery-friendly cadence for the AI-availability refresh (battery audit H1).
 *
 * Extracted from HomeViewModel so the timing math is pure Kotlin, unit-
 * testable without Android dependencies — the same pattern as the call
 * package's FallbackPollCadence.
 *
 * The refresh only ever runs while the app process is STARTED (lifecycle-
 * gated by ProcessLifecycleOwner in HomeViewModel) AND the signaling socket
 * is CONNECTED (collectLatest keys each cycle to actual CONNECTED
 * emissions). Backgrounded, parked or disconnected states therefore cost
 * zero requests: rings arrive via FCM/WS push regardless, and stale chips
 * are refreshed the moment the socket comes back — an acceptable trade for
 * not waking the radio all night, which matters most for users who granted
 * battery-optimization exemption because Doze will never mask this loop for
 * them.
 */
object AvailabilityPollCadence {

    /** Steady-state foreground cadence. 15 s was never justified: the chips show agent online/busy/offline, nothing that moves faster than a minute. */
    const val CONNECTED_MS = 60_000L

    /**
     * Exponential ladder on consecutive failures. Starts at [CONNECTED_MS]
     * so a single failed request never polls more aggressively than the
     * healthy path.
     */
    val FAILURE_BACKOFF_MS = longArrayOf(
        CONNECTED_MS,
        120_000L,
        240_000L,
        480_000L,
    )

    /**
     * Pure-function delay calculator for the next availability request.
     *
     * @param lastPollSucceeded false while the last attempt failed
     * @param failureStreak consecutive failures since the last success
     *   (>= 1 when [lastPollSucceeded] is false); the delay after failure N
     *   is ladder rung N-1, clamped into [FAILURE_BACKOFF_MS] — so the first
     *   failure costs no more than the healthy cadence and each further
     *   failure doubles the wait up to the longest rung.
     */
    fun computeDelayMs(lastPollSucceeded: Boolean, failureStreak: Int): Long {
        if (lastPollSucceeded) return CONNECTED_MS
        val rung = (failureStreak - 1).coerceIn(0, FAILURE_BACKOFF_MS.lastIndex)
        return FAILURE_BACKOFF_MS[rung]
    }
}
