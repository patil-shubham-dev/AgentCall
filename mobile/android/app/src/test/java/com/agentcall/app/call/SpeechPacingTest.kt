package com.agentcall.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tests for [SpeechPacing] — the sentence splitter and per-sentence pacing
 * (pause + speed jitter) that give the Piper TTS replies a natural cadence
 * (backlog item 11).
 *
 * Pure Kotlin (Random injected), so the jitter bounds are verifiable without
 * a device: every produced delay/speed must stay inside the documented range.
 */
class SpeechPacingTest {

    // ── Sentence splitting ────────────────────────────────────────────────

    @Test
    fun `splits on terminal punctuation`() {
        val sentences = SpeechPacing.splitIntoSentences("Hello there. How are you? Great!")
        assertEquals(listOf("Hello there.", "How are you?", "Great!"), sentences)
    }

    @Test
    fun `keeps abbreviations intact`() {
        val sentences = SpeechPacing.splitIntoSentences("Call Dr. Smith at noon. He is in.")
        assertEquals(listOf("Call Dr. Smith at noon.", "He is in."), sentences)
    }

    @Test
    fun `handles ellipsis and exclamation`() {
        val sentences = SpeechPacing.splitIntoSentences("Wait... I see it now!")
        assertEquals(listOf("Wait...", "I see it now!"), sentences)
    }

    @Test
    fun `drops blank fragments`() {
        val sentences = SpeechPacing.splitIntoSentences("One.   Two.  ")
        assertEquals(listOf("One.", "Two."), sentences)
    }

    @Test
    fun `blank input yields no sentences`() {
        assertTrue(SpeechPacing.splitIntoSentences("   ").isEmpty())
    }

    // ── Pacing jitter ─────────────────────────────────────────────────────

    @Test
    fun `sentence delay stays within 300-500ms bounds`() {
        val random = Random(42L)
        repeat(500) {
            val delay = SpeechPacing.sentenceDelayMs(random)
            assertTrue("delay out of range: $delay", delay in 300..500)
        }
    }

    @Test
    fun `sentence speed stays within 6 percent of normal`() {
        val random = Random(7L)
        repeat(500) {
            val speed = SpeechPacing.sentenceSpeed(random)
            assertTrue("speed out of range: $speed", speed in 0.94f..1.06f)
        }
    }

    // ── Synthesis chunking ───────────────────────────────────────────────

    @Test
    fun `long sentences never exceed the hard synthesis word limit`() {
        val text = (1..25).joinToString(" ") { "word$it" } + "."

        val chunks = SpeechPacing.chunkForSynthesis(text)

        assertTrue(chunks.size > 1)
        assertTrue(
            "chunks exceeded ${SpeechPacing.PacingConfig.MAX_WORDS_PER_CHUNK} words: $chunks",
            chunks.all { chunk ->
                chunk.text.trim().split(Regex("\\s+")).size <= SpeechPacing.PacingConfig.MAX_WORDS_PER_CHUNK
            },
        )
        assertEquals(text, chunks.joinToString(" ") { it.text })
        assertTrue(chunks.dropLast(1).all { !it.isLastInSentence })
        assertTrue(chunks.last().isLastInSentence)
    }

    @Test
    fun `chunking preserves sentence endings and marks each sentence final chunk`() {
        val first = (1..14).joinToString(" ") { "first$it" } + "."
        val second = "Second sentence is short."

        val chunks = SpeechPacing.chunkForSynthesis("$first $second")

        assertEquals("$first $second", chunks.joinToString(" ") { it.text })
        assertEquals(2, chunks.count { it.isLastInSentence })
        assertTrue(chunks.last().isLastInSentence)
        assertTrue(chunks.all { chunk ->
            chunk.text.trim().split(Regex("\\s+")).size <= SpeechPacing.PacingConfig.MAX_WORDS_PER_CHUNK
        })
    }
}
