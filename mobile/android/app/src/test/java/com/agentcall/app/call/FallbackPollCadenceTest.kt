package com.agentcall.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [FallbackPollCadence.computeDelayMs] — the adaptive battery-friendly
 * poll cadence that backs off from 10 s → 60 s → 5 min as the phone idles.
 *
 * Every tier boundary and Doze modulo is exercised here so regressions are
 * caught without needing a device.
 */
class FallbackPollCadenceTest {

    private val now = 1_000_000_000L // arbitrary epoch-ms anchor

    // ── Active tier ──────────────────────────────────────────────────────

    @Test
    fun `foreground returns ACTIVE_MS`() {
        assertEquals(
            FallbackPollCadence.ACTIVE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = true,
                hasActiveRing = false,
                lastActivityMs = now,
                idlePollStreak = 0,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `ring in flight returns ACTIVE_MS even when backgrounded`() {
        assertEquals(
            FallbackPollCadence.ACTIVE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = true,
                lastActivityMs = now - 10 * 60_000, // 10 min ago
                idlePollStreak = 50,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `within active window returns ACTIVE_MS`() {
        assertEquals(
            FallbackPollCadence.ACTIVE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now - 4 * 60_000, // 4 min ago (< 5 min window)
                idlePollStreak = 0,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `exactly at active window boundary returns BACKGROUND_MS`() {
        assertEquals(
            FallbackPollCadence.BACKGROUND_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now - FallbackPollCadence.ACTIVE_WINDOW_MS,
                idlePollStreak = 0,
                nowMs = now,
            ),
        )
    }

    // ── Background tier ──────────────────────────────────────────────────

    @Test
    fun `background within streak limit returns BACKGROUND_MS`() {
        assertEquals(
            FallbackPollCadence.BACKGROUND_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now - 10 * 60_000, // 10 min ago
                idlePollStreak = 10, // ≤ 20
                nowMs = now,
            ),
        )
    }

    @Test
    fun `background at streak limit returns BACKGROUND_MS`() {
        assertEquals(
            FallbackPollCadence.BACKGROUND_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now - 10 * 60_000,
                idlePollStreak = FallbackPollCadence.MAX_BACKGROUND_STREAK, // exactly 20
                nowMs = now,
            ),
        )
    }

    // ── Idle tier ────────────────────────────────────────────────────────

    @Test
    fun `background past streak limit returns IDLE_MS`() {
        assertEquals(
            FallbackPollCadence.IDLE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now - 30 * 60_000, // 30 min ago
                idlePollStreak = FallbackPollCadence.MAX_BACKGROUND_STREAK + 1, // 21
                nowMs = now,
            ),
        )
    }

    @Test
    fun `deep idle with high streak returns IDLE_MS`() {
        assertEquals(
            FallbackPollCadence.IDLE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now - 120 * 60_000, // 2 hours ago
                idlePollStreak = 200,
                nowMs = now,
            ),
        )
    }

    // ── Doze tier ────────────────────────────────────────────────────────

    @Test
    fun `doze non-multiple returns DOZE_SKIP_MS`() {
        // streak=0 → (0+1)%3 = 1 → skip
        assertEquals(
            FallbackPollCadence.DOZE_SKIP_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = true,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now,
                idlePollStreak = 0,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `doze every-third returns IDLE_MS`() {
        // streak=2 → (2+1)%3 = 0 → poll
        assertEquals(
            FallbackPollCadence.IDLE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = true,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now,
                idlePollStreak = 2,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `doze streak 5 returns DOZE_SKIP_MS`() {
        // streak=5 → (5+1)%3 = 0 → poll (every 3rd)
        assertEquals(
            FallbackPollCadence.IDLE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = true,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now,
                idlePollStreak = 5,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `doze streak 4 returns DOZE_SKIP_MS`() {
        // streak=4 → (4+1)%3 = 2 → skip
        assertEquals(
            FallbackPollCadence.DOZE_SKIP_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = true,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now,
                idlePollStreak = 4,
                nowMs = now,
            ),
        )
    }

    // ── Doze overrides foreground (highest priority) ─────────────────────

    @Test
    fun `doze overrides foreground — returns DOZE not ACTIVE`() {
        assertEquals(
            FallbackPollCadence.DOZE_SKIP_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = true,
                isForeground = true, // foreground but Doze wins
                hasActiveRing = false,
                lastActivityMs = now,
                idlePollStreak = 0,
                nowMs = now,
            ),
        )
    }

    // ── Tier transitions ─────────────────────────────────────────────────

    @Test
    fun `transition from background to idle at streak boundary`() {
        // streak=20 → BACKGROUND, streak=21 → IDLE
        assertEquals(
            FallbackPollCadence.BACKGROUND_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now - 10 * 60_000,
                idlePollStreak = 20,
                nowMs = now,
            ),
        )
        assertEquals(
            FallbackPollCadence.IDLE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = false,
                lastActivityMs = now - 10 * 60_000,
                idlePollStreak = 21,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `active ring overrides idle tier`() {
        assertEquals(
            FallbackPollCadence.ACTIVE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = false,
                hasActiveRing = true,
                lastActivityMs = now - 60 * 60_000, // 1 hour ago
                idlePollStreak = 100, // deep idle
                nowMs = now,
            ),
        )
    }

    @Test
    fun `foreground overrides deep idle`() {
        assertEquals(
            FallbackPollCadence.ACTIVE_MS,
            FallbackPollCadence.computeDelayMs(
                isDeviceIdle = false,
                isForeground = true,
                hasActiveRing = false,
                lastActivityMs = now - 60 * 60_000,
                idlePollStreak = 100,
                nowMs = now,
            ),
        )
    }

    // ── Constants sanity ─────────────────────────────────────────────────

    @Test
    fun `constants are consistent with documented values`() {
        assertEquals(10_000L, FallbackPollCadence.ACTIVE_MS)
        assertEquals(60_000L, FallbackPollCadence.BACKGROUND_MS)
        assertEquals(300_000L, FallbackPollCadence.IDLE_MS)
        assertEquals(5 * 60_000L, FallbackPollCadence.ACTIVE_WINDOW_MS)
        assertEquals(300_000L, FallbackPollCadence.DOZE_SKIP_MS)
        assertEquals(3, FallbackPollCadence.DOZE_EVERY)
        assertEquals(20, FallbackPollCadence.MAX_BACKGROUND_STREAK)
    }

    // ── shouldRunFallbackPoll (battery audit M4 gate) ────────────────────
    //
    // Truth table: poll only when DISCONNECTED && !parked && (fg || ring).

    @Test
    fun `gate polls when disconnected foreground and not parked`() {
        assert(
            FallbackPollCadence.shouldRunFallbackPoll(
                isDisconnected = true,
                isParked = false,
                isForeground = true,
                hasActiveRing = false,
            )
        )
    }

    @Test
    fun `gate polls when disconnected with ring in flight and not parked`() {
        assert(
            FallbackPollCadence.shouldRunFallbackPoll(
                isDisconnected = true,
                isParked = false,
                isForeground = false,
                hasActiveRing = true,
            )
        )
    }

    @Test
    fun `gate never polls while parked even if foreground`() {
        // Parked means the socket is deliberately down and rings come via FCM;
        // a foreground app restores the socket through connectIfIdle() instead.
        assert(
            !FallbackPollCadence.shouldRunFallbackPoll(
                isDisconnected = true,
                isParked = true,
                isForeground = true,
                hasActiveRing = true,
            )
        )
    }

    @Test
    fun `gate treats CONNECTING as wait not poll`() {
        // isDisconnected=false models CONNECTING/RECONNECTING/CONNECTED alike:
        // only a genuinely DISCONNECTED socket justifies polling.
        assert(
            !FallbackPollCadence.shouldRunFallbackPoll(
                isDisconnected = false,
                isParked = false,
                isForeground = true,
                hasActiveRing = true,
            )
        )
    }

    @Test
    fun `gate does not poll when idle in background without ring`() {
        assert(
            !FallbackPollCadence.shouldRunFallbackPoll(
                isDisconnected = true,
                isParked = false,
                isForeground = false,
                hasActiveRing = false,
            )
        )
    }
}
