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
        // Sub-chunk split (Fix 2): intra-sentence splits for cancellability must NOT get sentence-level pause.
        const val SUB_CHUNK_PAUSE_MS = 70L
        const val SUB_CHUNK_JITTER_MS = 30L
        const val MAX_WORDS_PER_CHUNK = 12
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

    /**
     * Fix 2 — bound worst-case generate() length.
     * Any sentence over [PacingConfig.MAX_WORDS_PER_CHUNK] (~12 words, ~0.8–1.0s synth at RTF 0.2)
     * is further split on commas/conjunctions/clause boundaries so no single generate() exceeds ~1s audio.
     * Returns chunks annotated with [isLastInSentence] so callers can distinguish real sentence ends
     * (full pause) from sub-chunk seams (micro-pause).
     */
    data class Chunk(val text: String, val isLastInSentence: Boolean)

    private val conjunctions = setOf(
        "and", "but", "or", "so", "yet", "however", "which", "that", "because",
        "while", "when", "where", "although", "though", "if", "as", "then", "thus", "therefore"
    )

    fun chunkForSynthesis(text: String): List<Chunk> {
        val sentences = splitIntoSentences(text)
        if (sentences.isEmpty()) return emptyList()
        val out = mutableListOf<Chunk>()
        for (sentence in sentences) {
            val subs = splitLongSentence(sentence, PacingConfig.MAX_WORDS_PER_CHUNK)
            for (i in subs.indices) {
                out += Chunk(subs[i], isLastInSentence = i == subs.lastIndex)
            }
        }
        return out
    }

    private fun splitLongSentence(sentence: String, maxWords: Int): List<String> {
        val words = sentence.trim().split(Regex("\\s+"))
        if (words.size <= maxWords) return listOf(sentence.trim())
        // Natural break after word index i if: word ends with , ; : — –  OR next word is conjunction
        val naturalBreaks = mutableSetOf<Int>() // word index after which to allow split (0-based, break after i)
        for (i in words.indices) {
            val w = words[i]
            if (w.endsWith(",") || w.endsWith(";") || w.endsWith(":") || w.endsWith("—") || w.endsWith("–")) {
                naturalBreaks.add(i)
            }
            if (i + 1 < words.size) {
                val nextLower = words[i + 1].lowercase().trimEnd(',', ';', ':', '.', '!', '?')
                if (nextLower in conjunctions) {
                    naturalBreaks.add(i) // break before conjunction (i.e., after current word)
                }
            }
        }

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            var end = minOf(start + maxWords, words.size) // exclusive
            if (end - start <= maxWords && end < words.size) {
                // Look backwards for a natural break within the window to prefer it over hard split
                var bestBreak: Int? = null
                for (b in naturalBreaks) {
                    if (b in start until end && b >= start + 3) { // avoid tiny 1-2 word leading chunk
                        if (bestBreak == null || b > bestBreak) bestBreak = b
                    }
                }
                if (bestBreak != null) {
                    end = bestBreak + 1
                } else if (end < words.size) {
                    // No natural break: hard split at maxWords (mid-clause) — unavoidable
                    // keep end as maxWords; prosody seam flagged in report
                }
            }
            // If this is the last chunk and remainder is tiny (1-2 words), merge with previous to avoid click
            if (words.size - end in 1..2 && chunks.isNotEmpty() && (end - start) > 4) {
                // merge tiny tail into this chunk
                end = words.size
            }
            val chunkText = words.subList(start, end).joinToString(" ")
            chunks += chunkText
            start = end
        }
        return chunks
    }

    /** Pause after a chunk: real sentence end uses full delay, sub-chunk uses micro-pause. */
    fun delayAfterChunk(chunk: Chunk, originalSentence: String? = null, random: Random = Random.Default): Long {
        return if (chunk.isLastInSentence) {
            // Real boundary — use original sentence's terminal punctuation if available
            delayAfterSentence(originalSentence ?: chunk.text, random)
        } else {
            PacingConfig.SUB_CHUNK_PAUSE_MS + random.nextLong(PacingConfig.SUB_CHUNK_JITTER_MS + 1)
        }
    }
}