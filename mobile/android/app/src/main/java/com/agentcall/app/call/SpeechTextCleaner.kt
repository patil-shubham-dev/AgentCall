package com.agentcall.app.call

/**
 * Speech → clean text for the AI boundary.
 *
 * Streaming recognizers hand back messy hypotheses ("um can you help me with
 * this uh"). Cleaning here — not in the prompt — keeps the agent-facing
 * transcript readable (Whisper-style flow output). Conservative by design:
 * only unambiguous fillers at the phrase edges are dropped, so the user's
 * meaning is never rewritten.
 */
object SpeechTextCleaner {

    // Onomatopoeic fillers only: dropping "um/uh/er/ah/hmm/mhm" can never
    // change a sentence's meaning. Content words ("like", "well",...) stay.
    private val FILLERS = setOf(
        "um", "umm", "ummm",
        "uh", "uhh", "uhhh",
        "er", "err",
        "ah", "ahh",
        "eh",
        "hmm", "hmmm", "mm", "mmm",
        "mhm", "mmhmm",
    )

    fun clean(raw: String): String {
        val trimmed = raw
            .replace(Regex("\\s+"), " ")
            .trim()
        if (trimmed.isEmpty()) return ""
        val tokens = trimmed.split(" ")
        var start = 0
        var end = tokens.size
        while (start < end && tokens[start].withoutPunctuation().lowercase() in FILLERS) start++
        while (end > start && tokens[end - 1].withoutPunctuation().lowercase() in FILLERS) end--
        val kept = tokens.subList(start, end).joinToString(" ")
        if (kept.isEmpty()) return ""
        val polished = kept.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
        return if (polished.last().isLetterOrDigit() || polished.last() == ',') "$polished." else polished
    }

    private fun String.withoutPunctuation(): String =
        this.trim('.')
            .trim('!')
            .trim('?')
            .trim(',')
}
