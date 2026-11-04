package com.agentcall.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallPresenceTest {

    private fun presence(
        phase: CallPhase = CallPhase.ACTIVE,
        isRecording: Boolean = false,
        isProcessing: Boolean = false,
        isAiSpeaking: Boolean = false,
        aiResponding: Boolean? = null,
        isMuted: Boolean = false,
        agentOnline: Boolean = true,
    ) = callPresence(phase, isRecording, isProcessing, isAiSpeaking, aiResponding, isMuted, agentOnline)

    @Test
    fun `terminal phases win`() {
        assertEquals(CallPresence.ENDED, presence(phase = CallPhase.ENDED, isRecording = true))
        assertEquals(
            CallPresence.RECONNECTING,
            presence(phase = CallPhase.RECONNECTING, isAiSpeaking = true),
        )
    }

    @Test
    fun `muted wins over voice activity`() {
        assertEquals(CallPresence.MUTED, presence(isMuted = true, isAiSpeaking = true))
    }

    @Test
    fun `speaking beats processing and thinking`() {
        assertEquals(CallPresence.SPEAKING, presence(isAiSpeaking = true, isProcessing = true))
        assertEquals(CallPresence.SPEAKING, presence(isAiSpeaking = true, aiResponding = true))
    }

    @Test
    fun `agent computing surfaces as thinking while mic stays open`() {
        // Hands-free: the mic never closes between turns, so "thinking" has
        // no isRecording requirement — it is the honest label while waiting.
        assertEquals(CallPresence.THINKING, presence(isProcessing = true))
        assertEquals(CallPresence.THINKING, presence(aiResponding = true))
        assertEquals(CallPresence.THINKING, presence(isProcessing = true, isRecording = true))
    }

    @Test
    fun `offline agent surfaces as error when nothing is moving`() {
        assertEquals(CallPresence.ERROR, presence(agentOnline = false))
        // A reply already in flight still shows what it is doing first.
        assertEquals(CallPresence.THINKING, presence(aiResponding = true, agentOnline = false))
    }

    @Test
    fun `hands-free default is listening`() {
        // No flags at all: the mic is always captured, listening is normal.
        assertEquals(CallPresence.LISTENING, presence())
        assertEquals(CallPresence.LISTENING, presence(isRecording = true))
    }

    @Test
    fun `candidate idle states map squarely`() {
        assertEquals(CallPresence.CONNECTING, presence(phase = CallPhase.CONNECTING))
        assertEquals(CallPresence.CONNECTING, presence(phase = CallPhase.RINGING))
    }

    @Test
    fun `labels exist for every presence`() {
        CallPresence.values().forEach { p ->
            assertEquals(false, p.label.isBlank())
        }
    }
}
