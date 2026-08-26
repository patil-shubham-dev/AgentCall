package com.agentcall.app.call

import kotlin.random.Random

/**
 * Natural-speech pacing for the piper TTS path (backlog item 14).
 *
 * System TTS reads an entire message as one flat utterance — no breathing
 * room, no variation. The piper engine is fed one sentence at a time, so the
 * pause structure of real speech is cheap to add here:
 * - [splitIntoSentences]: split on terminal punctuation followed by
 *   whitespace, but NOT after a known abbreviation ("Dr.", "St.", "U.S.",
 *   "etc.") or a single-letter initial ("J. Smith") — those periods belong
 *   to the word, not the sentence.
 * - [sentenceDelayMs]: 300-500ms of silence before every sentence but the
 *   first of a message.
 * - [sentenceSpeed]: ±6% speed jitter per sentence so consecutive sentences
 *   never land on the same robotic cadence.
 *
 * Pure Kotlin (Random injectable) so the boundaries are unit-testable without
 * a device.
 */
object SpeechPacing {

    // Punctuation run (incl. ellipses) followed by whitespace or end-of-text.
    private val boundaryRegex = Regex("[.!?…]+(?=\\s|$)")
    // A trailing period that belongs to an abbreviation (or single-letter
    // initial) is NOT a sentence boundary. Case-insensitive; `\b` anchors the
    // word so "apple. Next" still splits ("apple" is not an abbreviation).
    private val abbreviationRegex = Regex(
        "(?i)\\b(?:(?:dr|mr|mrs|ms|prof|rev|sr|jr|st|mt|etc|vs|e\\.?g|i\\.?e|" +
            "u\\.?s|u\\.?k|a\\.?m|p\\.?m|approx|inc|ltd|co|corp|est|dept|min|max)|[A-Za-z])\\.$"
    )
    private const val MIN_PAUSE_MS = 300L
    private const val PAUSE_JITTER_MS = 200L
    private const val SPEED_JITTER = 0.06f

    fun splitIntoSentences(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()
        val sentences = mutableListOf<String>()
        // sentenceStart accumulates the current sentence; scanPos walks the
        // text so an abbreviation-skip never re-matches the same boundary.
        var sentenceStart = 0
        var scanPos = 0
        var match = boundaryRegex.find(trimmed)
        while (match != null) {
            val punctEnd = match.range.first + match.value.length
            // The candidate includes the punctuation run, so the abbreviation
            // check sees the trailing period it must reject ("Dr.").
            val candidate = trimmed.substring(sentenceStart, punctEnd)
            if (!abbreviationRegex.containsMatchIn(candidate)) {
                sentences += candidate.trim()
                sentenceStart = punctEnd
            }
            scanPos = punctEnd
            match = boundaryRegex.find(trimmed, scanPos)
        }
        val tail = trimmed.substring(sentenceStart).trim()
        if (tail.isNotBlank()) sentences += tail
        return sentences
    }

    fun sentenceDelayMs(random: Random = Random.Default): Long =
        MIN_PAUSE_MS + random.nextLong(PAUSE_JITTER_MS + 1)

    fun sentenceSpeed(random: Random = Random.Default): Float =
        1.0f + (random.nextFloat() * 2f - 1f) * SPEED_JITTER
}