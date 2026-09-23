package com.agentcall.app.call

/**
 * Pure policy for the FGS-free ring leg (option B, 2026-09-12 P0).
 *
 * The 60s ring timeout no longer lives on a foreground service (starting one
 * from a backgrounded push is what crashed the app: dataSync quota first,
 * phoneCall Telecom requirements after). It lives on an exact
 * allow-while-idle alarm firing [RingTimeoutReceiver], which re-checks server
 * state before declining. Everything here is side-effect free so it stays
 * unit-testable without Robolectric (JUnit4 only, like the other cadence
 * helpers).
 */
object RingTimeoutPolicy {
    /** Ring window: matches the legacy FGS ringTimeoutJob / in-UI countdown. */
    const val TIMEOUT_MS = 60_000L

    /**
     * Fire the timeout decline only while the server still reports the call
     * as ringing. Any other state — answered (active/paused), finished, or
     * unknown (network failure) — skips: killing a live call is the worse
     * failure, and the server's own pending-TTL sweep bounds an unanswered
     * ring anyway. Null (unreachable backend) is conservative skip, not fire.
     */
    fun shouldFireTimeoutCancel(serverStatus: String?): Boolean =
        serverStatus == "pending"

    /** Absolute trigger for the timeout alarm, anchored at ring-post time. */
    fun triggerAtMs(ringPostedAtMs: Long): Long = ringPostedAtMs + TIMEOUT_MS

    /**
     * Stable alarm request code per call so schedule/cancel address the same
     * PendingIntent and simultaneous rings can't collide.
     */
    fun alarmRequestCode(callId: String): Int = callId.hashCode()
}
