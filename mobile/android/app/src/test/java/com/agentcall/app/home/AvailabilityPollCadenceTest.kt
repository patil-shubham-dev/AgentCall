package com.agentcall.app.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [AvailabilityPollCadence.computeDelayMs] — the battery-friendly
 * availability refresh cadence (battery audit H1).
 *
 * The lifecycle gating (ProcessLifecycleOwner STARTED + collectLatest on
 * connectionState) is structural in HomeViewModel and cannot be unit-tested
 * without instrumentation; what IS pure logic — "a healthy foreground poll
 * never runs faster than 60 s and failures back off exponentially" — is
 * pinned here.
 */
class AvailabilityPollCadenceTest {

    // ── Healthy path ─────────────────────────────────────────────────────

    @Test
    fun `success always waits CONNECTED_MS regardless of streak`() {
        assertEquals(AvailabilityPollCadence.CONNECTED_MS, AvailabilityPollCadence.computeDelayMs(lastPollSucceeded = true, failureStreak = 0))
        assertEquals(AvailabilityPollCadence.CONNECTED_MS, AvailabilityPollCadence.computeDelayMs(lastPollSucceeded = true, failureStreak = 3))
        assertEquals(AvailabilityPollCadence.CONNECTED_MS, AvailabilityPollCadence.computeDelayMs(lastPollSucceeded = true, failureStreak = 99))
    }

    @Test
    fun `connected cadence is at least 60 seconds`() {
        // Battery audit H1 requirement: the success-path interval must never
        // drop below one minute (15 s was the pre-fix value).
        assert(AvailabilityPollCadence.CONNECTED_MS >= 60_000L)
    }

    // ── Failure ladder ───────────────────────────────────────────────────

    @Test
    fun `first failure starts the ladder at CONNECTED_MS`() {
        assertEquals(
            AvailabilityPollCadence.FAILURE_BACKOFF_MS[0],
            AvailabilityPollCadence.computeDelayMs(lastPollSucceeded = false, failureStreak = 1),
        )
        assertEquals(
            AvailabilityPollCadence.CONNECTED_MS,
            AvailabilityPollCadence.FAILURE_BACKOFF_MS[0],
        )
    }

    @Test
    fun `consecutive failures walk the ladder exponentially`() {
        assertEquals(120_000L, AvailabilityPollCadence.computeDelayMs(false, failureStreak = 2))
        assertEquals(240_000L, AvailabilityPollCadence.computeDelayMs(false, failureStreak = 3))
        assertEquals(480_000L, AvailabilityPollCadence.computeDelayMs(false, failureStreak = 4))
    }

    @Test
    fun `failure past the ladder end clamps to the longest delay`() {
        assertEquals(480_000L, AvailabilityPollCadence.computeDelayMs(false, failureStreak = 10))
        assertEquals(480_000L, AvailabilityPollCadence.computeDelayMs(false, failureStreak = Int.MAX_VALUE))
    }

    @Test
    fun `negative streak clamps to shortest delay instead of crashing`() {
        assertEquals(
            AvailabilityPollCadence.FAILURE_BACKOFF_MS[0],
            AvailabilityPollCadence.computeDelayMs(false, failureStreak = -5),
        )
    }

    // ── Ladder shape ─────────────────────────────────────────────────────

    @Test
    fun `ladder is monotonically non-decreasing`() {
        val ladder = AvailabilityPollCadence.FAILURE_BACKOFF_MS
        for (i in 1 until ladder.size) {
            assert(ladder[i] >= ladder[i - 1]) {
                "ladder[$i]=${ladder[i]} < ladder[${i - 1}]=${ladder[i - 1]}"
            }
        }
    }

    @Test
    fun `no rung is faster than the healthy cadence`() {
        // A failed request must never cause a tighter loop than a successful
        // one — that was the original bug's shape (fixed 15 s hammering).
        for (rung in AvailabilityPollCadence.FAILURE_BACKOFF_MS) {
            assert(rung >= AvailabilityPollCadence.CONNECTED_MS)
        }
    }
}
