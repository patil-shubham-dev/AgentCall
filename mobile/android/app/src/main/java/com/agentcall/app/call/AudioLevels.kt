package com.agentcall.app.call

import kotlin.math.sqrt

/** Normalized 0..1 energy for the two sides of the conversation. */
data class AudioEnergy(val user: Float = 0f, val ai: Float = 0f)

/**
 * Pure amplitude buffer for the call aura.
 *
 * Both real audio streams feed it — mic RMS during listening
 * ([StreamingAsrEngine] per-frame dB) and TTS chunk RMS during playback
 * (Piper PCM) — so the visualization reflects actual audio
 * amplitude, never synthetic movement. Energy is reported per stream so the
 * mirrored call aura can react to each side independently; idle buffers decay
 * toward the quiet baseline instead of freezing at the last gesture.
 */
class AudioLevels(slots: Int = DEFAULT_SLOTS) {

    private val mic = FloatArray(slots) { BASELINE }
    private val tts = FloatArray(slots) { BASELINE }
    private var micIndex = 0
    private var ttsIndex = 0
    private var rollingMax = 0f

    /** Called from the recognizer thread; slots are written round-robin. */
    @Synchronized
    fun onMic(level: Float) {
        write(mic, micIndex++, normalizeMic(level))
    }

    /** Called from the TTS playback thread per written chunk. */
    @Synchronized
    fun onTts(level: Float) {
        write(tts, ttsIndex++, normalizeTts(level))
    }

    private fun write(buf: FloatArray, index: Int, level: Float) {
        buf[index % buf.size] = level
        if (index >= buf.size * 10) {
            // Keep indices small and well-behaved after long calls. `index` is
            // the slot just written, so the next write is index + 1.
            val next = (index + 1) % buf.size
            if (buf === mic) micIndex = next else ttsIndex = next
        }
    }

    /** Clears one stream to baseline (speech finished, mic released, mute). */
    @Synchronized
    fun reset(micStream: Boolean) {
        val buf = if (micStream) mic else tts
        buf.fill(BASELINE)
        if (micStream) {
            micIndex = 0
            rollingMax = 0f
        } else {
            ttsIndex = 0
        }
    }

    /**
     * Smoothly falls out-of-mode buffers back to baseline so a stream that
     * stopped emitting (speech ended, recognizer paused) stops showing its
     * last gesture.
     */
    @Synchronized
    fun decayTowardBaseline(listeningActive: Boolean, speakingActive: Boolean) {
        if (!listeningActive) decay(mic)
        if (!speakingActive) decay(tts)
    }

    private fun decay(buf: FloatArray) {
        for (i in buf.indices) {
            buf[i] = BASELINE + (buf[i] - BASELINE) * DECAY
        }
    }

    /**
     * Separate, normalized energy for the mirrored call aura. The user side is
     * honest to the mic stream (zero while the recognizer is not listening),
     * and the AI side is zero while the AI voice is muted. No synthetic values.
     */
    @Synchronized
    fun energyLevels(
        listeningActive: Boolean,
        speakingActive: Boolean,
        muted: Boolean,
    ): AudioEnergy {
        val user = if (listeningActive) normalizeEnergy(recentPeak(mic, micIndex)) else 0f
        val ai = if (speakingActive && !muted) normalizeEnergy(recentPeak(tts, ttsIndex)) else 0f
        return AudioEnergy(user = user, ai = ai)
    }

    private fun recentPeak(buf: FloatArray, index: Int): Float {
        var peak = BASELINE
        val window = minOf(RECENT_WINDOW, buf.size)
        for (i in 1..window) {
            peak = maxOf(peak, buf[(index - i + buf.size * 2) % buf.size])
        }
        return peak
    }

    private fun normalizeEnergy(peak: Float): Float =
        ((peak - BASELINE) / (1f - BASELINE)).coerceIn(0f, 1f)

    /**
     * Recognizer rmsdB is device-dependent (commonly a noisy -10..0 dB window).
     * The gate runs before normalization, and calibration only learns from a
     * clearly voiced sample. That prevents a quiet room from calibrating itself
     * up into a full-size visual gesture.
     */
    private fun normalizeMic(level: Float): Float {
        val normalized = ((level + 10f) / 10f).coerceIn(0f, 1f)
        if (normalized <= MIC_GATE) {
            rollingMax *= MAX_DECAY
            return BASELINE
        }
        if (normalized >= MIC_CALIBRATION_FLOOR) {
            rollingMax = maxOf(rollingMax * MAX_DECAY, normalized)
        } else {
            rollingMax *= MAX_DECAY
        }
        val scaled = if (rollingMax <= MIN_WINDOW) {
            normalized
        } else {
            (normalized / (rollingMax * SCALE_SOFT)).coerceIn(0f, 1f)
        }
        return normalizeAboveGate(scaled, MIC_POST_GATE)
    }

    /** TTS chunk RMS is 0..1 float PCM; sqrt gives perceptual loudness. */
    private fun normalizeTts(level: Float): Float {
        val scaled = (sqrt(level.coerceIn(0f, 1f)) * TTS_GAIN).coerceIn(0f, 1f)
        return normalizeAboveGate(scaled, TTS_GATE)
    }

    /** Removes sub-audible activity, then restores the remaining useful range. */
    private fun normalizeAboveGate(level: Float, threshold: Float): Float {
        if (level <= threshold) return BASELINE
        return ((level - threshold) / (1f - threshold)).coerceIn(0f, 1f)
    }

    companion object {
        const val DEFAULT_SLOTS = 40
        const val BASELINE = 0.07f
        private const val TTS_GAIN = 1.35f
        private const val SCALE_SOFT = 0.85f
        private const val MAX_DECAY = 0.98f
        private const val MIN_WINDOW = 0.25f
        private const val DECAY = 0.80f
        private const val RECENT_WINDOW = 3
        private const val MIC_GATE = 0.24f
        private const val MIC_POST_GATE = 0.08f
        private const val TTS_GATE = 0.07f
        private const val MIC_CALIBRATION_FLOOR = 0.45f
    }
}
