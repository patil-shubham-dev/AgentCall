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
    // Punctuation-aware pauses (tunable). Values chosen to sound natural
    // without overcorrecting into robotic gaps. All pauses include jitter
    // so consecutive sentences never land on the same cadence.
    object PacingConfig {
        const val COMMA_PAUSE_MS = 180L
        const val COMMA_JITTER_MS = 80L
        const val PERIOD_PAUSE_MS = 380L
        const val PERIOD_JITTER_MS = 120L
        const val QUESTION_PAUSE_MS = 520L
        const val QUESTION_JITTER_MS = 100L
        const val EXCLAMATION_PAUSE_MS = 320L
        const val EXCLAMATION_JITTER_MS = 80L
        // Fallback for sentences that don't end with punctuation (e.g. "Hello world")
        const val FALLBACK_PAUSE_MS = 280L
        const val FALLBACK_JITTER_MS = 80L
        // Speed jitter per sentence — keeps the voice from sounding flat.
        const val SPEED_JITTER = 0.06f
        // Overlap synthesis: start generating next sentence while current plays.
        // No pause needed for true streaming; this is for the sentence-boundary gap.
    }

    private const val SPEED_JITTER = PacingConfig.SPEED_JITTER

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
        PacingConfig.PERIOD_PAUSE_MS + random.nextLong(PacingConfig.PERIOD_JITTER_MS + 1)

    /** Punctuation-aware pause for the gap *after* [previousSentence]. */
    fun delayAfterSentence(previousSentence: String, random: Random = Random.Default): Long {
        val trimmed = previousSentence.trim()
        if (trimmed.isEmpty()) return sentenceDelayMs(random)
        val lastChar = trimmed.last()
        return when (lastChar) {
            ',' -> PacingConfig.COMMA_PAUSE_MS + random.nextLong(PacingConfig.COMMA_JITTER_MS + 1)
            '.', '…', '。' -> PacingConfig.PERIOD_PAUSE_MS + random.nextLong(PacingConfig.PERIOD_JITTER_MS + 1)
            '?' -> PacingConfig.QUESTION_PAUSE_MS + random.nextLong(PacingConfig.QUESTION_JITTER_MS + 1)
            '!' -> PacingConfig.EXCLAMATION_PAUSE_MS + random.nextLong(PacingConfig.EXCLAMATION_JITTER_MS + 1)
            ';', ':' -> PacingConfig.COMMA_PAUSE_MS + random.nextLong(PacingConfig.COMMA_JITTER_MS + 1)
            else -> PacingConfig.FALLBACK_PAUSE_MS + random.nextLong(PacingConfig.FALLBACK_JITTER_MS + 1)
        }
    }

    fun sentenceSpeed(random: Random = Random.Default): Float =
        1.0f + (random.nextFloat() * 2f - 1f) * SPEED_JITTER
}