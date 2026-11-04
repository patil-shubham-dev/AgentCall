package com.agentcall.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The aura is an honest meter, so each side only reports its own live stream
 * and buffers decay cleanly when that stream goes quiet.
 */
class AudioLevelsTest {

    @Test
    fun `energy is silent before anything happens`() {
        val levels = AudioLevels(slots = 8)
        val energy = levels.energyLevels(listeningActive = false, speakingActive = false, muted = false)
        assertEquals(0f, energy.user, 0f)
        assertEquals(0f, energy.ai, 0f)
    }

    @Test
    fun `muted AI keeps live mic energy because mute never gates the microphone`() {
        val levels = AudioLevels(slots = 8)
        repeat(10) { levels.onMic(-2f) }
        val energy = levels.energyLevels(listeningActive = true, speakingActive = false, muted = true)
        assertTrue("expected user energy, got ${energy.user}", energy.user > 0.3f)
        assertEquals(0f, energy.ai, 0f)
    }

    @Test
    fun `mic energy drives only the user side`() {
        val levels = AudioLevels(slots = 8)
        repeat(8) { levels.onMic(-2f) }
        val energy = levels.energyLevels(listeningActive = true, speakingActive = false, muted = false)
        assertTrue("expected user energy, got ${energy.user}", energy.user > 0.3f)
        assertEquals(0f, energy.ai, 0f)
    }

    @Test
    fun `tts energy drives only the AI side`() {
        val levels = AudioLevels(slots = 8)
        repeat(6) { levels.onTts(0.25f) }
        val energy = levels.energyLevels(listeningActive = false, speakingActive = true, muted = false)
        assertTrue("expected AI energy, got ${energy.ai}", energy.ai > 0.3f)
        assertEquals(0f, energy.user, 0f)
    }

    @Test
    fun `muted AI reports no energy even while chunks arrive`() {
        val levels = AudioLevels(slots = 8)
        repeat(6) { levels.onTts(0.9f) }
        val energy = levels.energyLevels(listeningActive = false, speakingActive = true, muted = true)
        assertEquals(0f, energy.ai, 0f)
    }

    @Test
    fun `inactive listening suppresses stale mic energy`() {
        val levels = AudioLevels(slots = 8)
        repeat(8) { levels.onMic(-1f) }
        val energy = levels.energyLevels(listeningActive = false, speakingActive = false, muted = false)
        assertEquals(0f, energy.user, 0f)
    }

    @Test
    fun `out-of-mode stream decays toward silence instead of freezing`() {
        val levels = AudioLevels(slots = 8)
        repeat(8) { levels.onTts(0.9f) }
        repeat(10) {
            levels.decayTowardBaseline(listeningActive = false, speakingActive = false)
        }
        val energy = levels.energyLevels(listeningActive = false, speakingActive = true, muted = false)
        assertTrue("expected decayed AI energy, got ${energy.ai}", energy.ai < 0.25f)
    }

    @Test
    fun `reset clears a stream immediately`() {
        val levels = AudioLevels(slots = 8)
        repeat(8) { levels.onMic(0.9f) }
        levels.reset(micStream = true)
        val energy = levels.energyLevels(listeningActive = true, speakingActive = false, muted = false)
        assertEquals(0f, energy.user, 0f)
    }
}
