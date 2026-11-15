package com.agentcall.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StageMathTest {

    @Test
    fun `composer heights match the four approved steps`() {
        assertEquals(44, StageMath.composerFieldHeight(1).value.toInt())
        assertEquals(66, StageMath.composerFieldHeight(2).value.toInt())
        assertEquals(88, StageMath.composerFieldHeight(3).value.toInt())
        assertEquals(110, StageMath.composerFieldHeight(4).value.toInt())
        assertEquals(110, StageMath.composerFieldHeight(9).value.toInt())
        assertEquals(44, StageMath.composerFieldHeight(0).value.toInt())
    }

    @Test
    fun `scrollport is the field minus its vertical padding`() {
        for (lines in 1..4) {
            assertEquals(
                StageMath.composerFieldHeight(lines).value - 22f,
                StageMath.composerScrollportHeight(lines).value,
                0.01f,
            )
        }
        assertEquals(22, StageMath.composerScrollportHeight(1).value.toInt())
        assertEquals(88, StageMath.composerScrollportHeight(4).value.toInt())
    }

    @Test
    fun `explicit newlines are a lower bound on line count`() {
        assertEquals(1, StageMath.explicitLineCount(""))
        assertEquals(3, StageMath.explicitLineCount("a\nb\nc"))
        assertEquals(4, StageMath.visualLineCount("a\nb", 4))
        assertEquals(3, StageMath.visualLineCount("a\nb\nc", 1))
    }

    @Test
    fun `draft is trimmed capped and rejected when blank`() {
        assertNull(StageMath.deliverableDraft("   \n  "))
        assertEquals("hello", StageMath.deliverableDraft("  hello \n"))
        val long = "x".repeat(500)
        assertEquals(400, StageMath.deliverableDraft(long)!!.length)
    }

    @Test
    fun `duration copy is m colon ss and never negative`() {
        assertEquals("0:00", StageMath.formatDuration(0))
        assertEquals("0:07", StageMath.formatDuration(7))
        assertEquals("1:24", StageMath.formatDuration(84))
        assertEquals("12:03", StageMath.formatDuration(723))
        assertEquals("0:00", StageMath.formatDuration(-4))
    }

    @Test
    fun `spoken duration spells the units out`() {
        assertEquals("0 seconds", StageMath.spokenDuration(0))
        assertEquals("7 seconds", StageMath.spokenDuration(7))
        assertEquals("1 minute", StageMath.spokenDuration(60))
        assertEquals("1 minute 24 seconds", StageMath.spokenDuration(84))
        assertEquals("12 minutes 3 seconds", StageMath.spokenDuration(723))
        assertEquals("0 seconds", StageMath.spokenDuration(-4))
    }

    @Test
    fun `sweep is time based and clamped`() {
        assertEquals(8f, StageMath.sweepStep(16.67f), 0.001f)
        assertEquals(4f, StageMath.sweepStep(8.335f), 0.001f)
        assertEquals(StageMath.sweepStep(48f), StageMath.sweepStep(500f), 0.001f)
        assertEquals(0f, StageMath.sweepStep(0f), 0.001f)
    }

    @Test
    fun `presence follows the stage precedence`() {
        fun presence(
            phase: CallPhase = CallPhase.ACTIVE,
            speaking: Boolean = false,
            processing: Boolean = false,
            responding: Boolean? = null,
            muted: Boolean = false,
            online: Boolean = true,
            mic: Float = 0f,
        ) = StageMath.stagePresence(phase, speaking, processing, responding, muted, online, mic)

        assertEquals(StagePresence.ENDED, presence(phase = CallPhase.ENDED, speaking = true))
        assertEquals(StagePresence.RECONNECTING, presence(phase = CallPhase.RECONNECTING, speaking = true))
        assertEquals(StagePresence.MUTED, presence(speaking = true, muted = true))
        assertEquals(StagePresence.SPEAKING, presence(speaking = true))
        assertEquals(StagePresence.THINKING, presence(responding = true))
        assertEquals(StagePresence.THINKING, presence(processing = true))
        assertEquals(StagePresence.CONNECTING, presence(phase = CallPhase.CONNECTING))
        assertEquals(StagePresence.CONNECTING, presence(phase = CallPhase.RINGING))
        assertEquals(StagePresence.OFFLINE, presence(online = false))
        assertEquals(StagePresence.LISTENING_TO_YOU, presence(mic = 0.2f))
        assertEquals(StagePresence.LISTENING, presence())
    }

    @Test
    fun `muted AI does not hide the human voice`() {
        val presence = StageMath.stagePresence(
            phase = CallPhase.ACTIVE,
            isAiSpeaking = false,
            isProcessing = false,
            aiResponding = null,
            aiVoiceMuted = true,
            agentOnline = true,
            userAudioLevel = 0.4f,
        )
        assertEquals(StagePresence.LISTENING_TO_YOU, presence)
    }

    @Test
    fun `route copy maps every real route kind`() {
        CallAudioManager.RouteKind.values().forEach { kind ->
            assertEquals(false, StageMath.routeShortLabel(kind).isBlank())
            assertEquals(false, StageMath.routeSubtitle(kind).isBlank())
        }
        assertEquals("Earpiece", StageMath.routeShortLabel(CallAudioManager.RouteKind.EARPIECE))
        assertEquals("Bluetooth · Connected", StageMath.routeSubtitle(CallAudioManager.RouteKind.BLUETOOTH))
    }
}
