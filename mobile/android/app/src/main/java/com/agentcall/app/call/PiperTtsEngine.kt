package com.agentcall.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Bundled offline TTS (backlog item 14): a Piper medium voice running on the
 * sherpa-onnx engine, extracted from assets on first use.
 *
 * System TextToSpeech depends on the device's installed voices — many cheap
 * devices have none, so the AI silently mutes itself mid-call (the classic
 * "no TTS voice" failure). Piper is fully offline and ships in the APK:
 *  - model: `assets/piper/en_US-hfc_female-medium.onnx` (63 MB)
 *  - lexicon: `assets/piper/tokens.txt`
 *  - phonemizer data: `assets/piper/espeak-ng-data/` (18 MB, full bundle —
 *    the dict/voice pairing is guaranteed to match the model)
 *
 * The 81 MB bundle is copied to filesDir once, then the ONNX model is loaded
 * via `OfflineTts(assetManager = null, ...)` (file-based, no AssetManager).
 * Playback uses a streaming AudioTrack with USAGE_VOICE_COMMUNICATION so the
 * audio follows the same routing as the rest of the call (earpiece by
 * default, loudspeaker when CallAudioManager toggles it, headset when
 * connected) — never the media stream.
 *
 * Streaming contract (verified in the engine source, sherpa-onnx
 * `offline-tts-vits-impl.h`): for text that fits in one batch (our
 * per-sentence calls always do) the callback receives the full sentence's
 * samples in ONE invocation and the return value is ignored; a stop request
 * therefore stops WRITING (instant silence) while synthesis finishes in
 * background — fine for sentence-length input.
 *
 * All methods except [isReady] are called on a background thread (the speech
 * worker in CallService); [init] blocks for seconds (81 MB copy + ONNX load).
 */
class PiperTtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var modelDir: File? = null
    private var totalFramesWritten = 0

    @Volatile
    var stopRequested = false
        private set

    // Drain signaling: the marker callback flips [drainComplete] and wakes any
    // waiter blocked on [drainLock]; requestStop() notifies too so an early
    // stop never waits out the deadline slice.
    private val drainLock = Object()

    @Volatile
    private var drainComplete = false

    val isReady: Boolean
        get() = tts != null

    val sampleRate: Int
        get() = tts?.sampleRate() ?: 22050

    /** Loads the engine + model. Returns false on any failure (caller falls back to system TTS). */
    fun init(): Boolean {
        if (tts != null) return true
        return try {
            val copyStart = System.currentTimeMillis()
            val dir = extractModelIfNeeded()
            val copyMs = System.currentTimeMillis() - copyStart
            Log.i(TAG, "[PIPER] copy check took ${copyMs}ms (should be 0-10ms if already extracted)")
            val onnxStart = System.currentTimeMillis()
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(dir, "en_US-hfc_female-medium.onnx").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                    ),
                    numThreads = 2,
                ),
            )
            tts = OfflineTts(config = config)
            stopRequested = false
            val onnxMs = System.currentTimeMillis() - onnxStart
            Log.i(TAG, "[PIPER] ONNX load took ${onnxMs}ms, ready sampleRate=${tts?.sampleRate()} speakers=${tts?.numSpeakers()}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "[PIPER] init failed — falling back to system TTS", e)
            release()
            false
        }
    }

    /** Synthesizes text to PCM without playing. Used for overlap: generate N+1 while N plays. */
    fun synthesize(text: String, speed: Float = 1.0f): com.k2fsa.sherpa.onnx.GeneratedAudio? {
        val engine = tts ?: return null
        if (text.isBlank() || stopRequested) return null
        return try {
            val t0 = System.nanoTime()
            val audio = engine.generate(text, 0, speed)
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            // Benchmark B3/B4: per-sentence synth timing vs audio duration (RTF logged by caller too; this is the engine-level stamp).
            val sr = tts?.sampleRate() ?: 22050
            val audioMs = if (audio != null) (audio.samples.size * 1000.0 / sr).toLong() else -1
            Log.d(TAG, "[PIPER] synth done chars=${text.length} synthMs=$dtMs audioMs=$audioMs")
            audio
        } catch (e: Throwable) {
            Log.e(TAG, "[PIPER] synthesize failed", e)
            null
        }
    }

    /** Plays already-synthesized audio. Separate from synthesize for overlap. */
    fun playAudio(audio: com.k2fsa.sherpa.onnx.GeneratedAudio): Boolean {
        if (stopRequested) return false
        val sampleRate = tts?.sampleRate() ?: return false
        val track = ensureAudioTrack(sampleRate) ?: return false
        return try {
            val tPlay = System.currentTimeMillis()
            track.play()
            Log.d(TAG, "[PIPER] play start samples=${audio.samples.size} sampleRate=$sampleRate")
            var offset = 0
            var firstWriteMs = 0L
            while (offset < audio.samples.size) {
                if (stopRequested) break
                val written = track.write(
                    audio.samples, offset, audio.samples.size - offset,
                    AudioTrack.WRITE_BLOCKING,
                )
                if (firstWriteMs == 0L) firstWriteMs = System.currentTimeMillis() - tPlay
                if (written < 0) {
                    Log.e(TAG, "[PIPER] write failed code=$written")
                    break
                }
                offset += written
                totalFramesWritten += written
            }
            if (stopRequested) Log.i(TAG, "[PIPER] play cut by barge-in after offset=$offset/${audio.samples.size} firstWriteMs=$firstWriteMs")
            else Log.d(TAG, "[PIPER] write complete firstWriteMs=$firstWriteMs written=$offset")
            waitForDrain(track)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "[PIPER] play failed", e)
            false
        }
    }

    /**
     * Synthesizes one sentence and plays it through the communication
     * audio path. Returns when the sentence has fully played out.
     * Kept for single-sentence callers; overlapping callers use synthesize()+playAudio().
     */
    fun speakSentence(text: String, speed: Float = 1.0f): Boolean {
        if (text.isBlank()) return false
        stopRequested = false
        val audio = synthesize(text, speed) ?: return false
        return playAudio(audio)
    }

    fun requestStop() {
        stopRequested = true
        synchronized(drainLock) { drainLock.notifyAll() }
    }

    /**
     * Clears a latched stop request so the NEXT message can play.
     *
     * [stopRequested] is deliberately sticky across [synthesize]/[playAudio]
     * (a barge-in must cancel every remaining chunk of the interrupted
     * message), but the single serialized speech worker means a new message
     * only ever starts after the interrupted one fully unwound — resetting
     * here cannot race an in-flight stop. Without this, one barge-in latches
     * the flag forever and every subsequent Piper message silently no-ops
     * (synthesize returns null on a set flag), leaving the call mute until
     * the engine is recycled.
     */
    fun startNewUtterance() {
        stopRequested = false
    }

    fun release() {
        stopRequested = true
        runCatching { tts?.release() }
        tts = null
        runCatching {
            audioTrack?.apply { pause(); flush(); release() }
        }
        audioTrack = null
        modelDir = null
        totalFramesWritten = 0
    }

    /**
     * Blocks until every written frame has actually played out (WRITE_BLOCKING
     * only guarantees the data reached the track's internal buffer, so the
     * tail of a sentence would otherwise be cut or bleed into the next one).
     *
     * Battery audit M3(a): drain completion is now an event, not a poll. The
     * notification marker is set at [totalFramesWritten] and
     * OnPlaybackPositionUpdateListener.onMarkerReached fires when the playback
     * head reaches it (API 3+; marker uses the same wrapping frame units as
     * getPlaybackHeadPosition — both sides accumulate identically across
     * sentences on a shared track). The waiter blocks on the monitor via
     * wait() — zero CPU wakeups while waiting — and only re-loops on the
     * callback, requestStop(), or a bounded deadline slice that guards against
     * devices with unreliable marker delivery.
     *
     * Threading: the track is created on a Dispatchers.Default worker with no
     * Looper, so per the AudioTrack contract the listener is dispatched on the
     * main looper; it therefore only sets a flag and notifies — no audio or
     * allocation work happens there.
     */
    private fun waitForDrain(track: AudioTrack) {
        val startMs = System.currentTimeMillis()
        val marker = totalFramesWritten
        synchronized(drainLock) {
            drainComplete = false
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        synchronized(drainLock) {
                            drainComplete = true
                            drainLock.notifyAll()
                        }
                    }

                    override fun onPeriodicNotification(t: AudioTrack?) {}
                }
            )
            // Marker after listener: at most one marker can be active; if the
            // head already passed this position before the native event fires,
            // some devices never deliver the callback.
            track.setNotificationMarkerPosition(marker)
            // Fast path for exactly that case: the head may already be at or
            // past the marker by the time we set it.
            if (track.playbackHeadPosition >= marker) drainComplete = true

            val deadlineMs = startMs + DRAIN_TIMEOUT_MS
            try {
                while (!drainComplete && !stopRequested &&
                    track.playState == AudioTrack.PLAYSTATE_PLAYING
                ) {
                    val remainMs = deadlineMs - System.currentTimeMillis()
                    if (remainMs <= 0) break
                    drainLock.wait(minOf(remainMs, DRAIN_WAIT_SLICE_MS))
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val elapsedMs = System.currentTimeMillis() - startMs
        if (!drainComplete) {
            Log.w(TAG, "[PIPER] drain timed out after $elapsedMs ms")
        } else {
            Log.d(TAG, "[PIPER] drained in ${elapsedMs}ms")
        }
    }

    private fun ensureAudioTrack(sampleRate: Int): AudioTrack? {
        audioTrack?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "[PIPER] getMinBufferSize failed ($minBuf)")
            return null
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        return track
    }

    private fun extractModelIfNeeded(): File {
        modelDir?.let { return it }
        val dest = File(context.filesDir, MODEL_ASSET_DIR)
        val onnx = File(dest, MODEL_ONNX)
        val tokens = File(dest, TOKENS)
        val espeak = File(dest, ESPEAK_DATA_DIR)
        if (!onnx.exists() || !tokens.exists() || !espeak.isDirectory) {
            Log.i(TAG, "[PIPER] extracting model bundle to ${dest.absolutePath}")
            dest.deleteRecursively()
            copyAssetTree(MODEL_ASSET_DIR, dest)
        }
        modelDir = dest
        return dest
    }

    /**
     * Recursively copies one asset path to disk. `assets.list()` returns the
     * children of a directory and null/empty for a file; this bundle contains
     * no empty directories, so null-or-empty means "file".
     */
    private fun copyAssetTree(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetTree("$assetPath/$child", File(dest, child))
        }
    }

    companion object {
        private const val TAG = "AgentCall"
        private const val MODEL_ASSET_DIR = "piper"
        private const val MODEL_ONNX = "en_US-hfc_female-medium.onnx"
        private const val TOKENS = "tokens.txt"
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"
        private const val DRAIN_TIMEOUT_MS = 5000L
        // Upper bound on a single wait() slice; the marker callback normally
        // wakes the waiter long before this. Guards against a device that
        // never delivers the marker event.
        private const val DRAIN_WAIT_SLICE_MS = 250L
    }
}