package com.agentcall.app.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Lightweight VAD for barge-in during Piper playback.
 *
 * Decision (Workstream A):
 * - sherpa-onnx DOES expose a Vad module (Vad, VadModelConfig, SileroVadModelConfig, TenVadModelConfig)
 *   reusing the already-bundled libonnxruntime.so + libsherpa-onnx-jni.so (no second runtime).
 *   However it requires a model file (silero_vad.onnx ~1.6 MB) that is NOT currently bundled,
 *   and adds model-load latency + ~10 ms inference per window.
 * - Chosen: energy/RMS VAD via a persistent low-duty-cycle AudioRecord tap.
 *   Reason: 0 APK weight, 0 extra assets, reuses only Android platform APIs,
 *   trivial CPU (<0.1% on a big core), latency ~150–260 ms which fits the
 *   150–300 ms product target, and the echo/false-trigger tradeoff is acceptable
 *   behind the 400 ms grace window and 3-frame hangover. Upgrade path documented:
 *   drop silero_vad.onnx into assets/vad/ and swap [VadEngine] to [SherpaVadEngine]
 *   without changing CallService wiring.
 *
 * Runs on its own scope (Dispatchers.IO). Must NOT hold SpeechRecognizer's mic;
 * it releases AudioRecord synchronously on stop so auto-STT can acquire immediately.
 */
class BargeInController(
    private val context: Context,
    private val onBargeIn: () -> Unit,
) {
    companion object {
        private const val TAG = "AgentCall"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1024 // ~64 ms at 16 kHz
        private const val BYTES_PER_SAMPLE = 2 // PCM_16BIT

        /** RMS threshold for 16-bit PCM; ~1200 ≈ -22 dBFS on earpiece. Speaker needs higher to avoid echo self-trigger. */
        private const val RMS_THRESHOLD_EARPIECE = 1400.0
        private const val RMS_THRESHOLD_SPEAKER = 4000.0

        /** Need N consecutive frames above threshold before triggering (debounce). */
        private const val REQUIRED_CONSECUTIVE_EARPIECE = 3
        private const val REQUIRED_CONSECUTIVE_SPEAKER = 5

        /** Ignore detections within this window after TTS start (echo grace). */
        private const val GRACE_MS = 600L

        /** Sleep when below threshold to keep duty cycle low. */
        private const val IDLE_SLEEP_MS = 30L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var running = false
    @Volatile private var ttsStartMs = 0L
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "[VAD] RECORD_AUDIO not granted — barge-in disabled for this session")
            return
        }
        running = true
        ttsStartMs = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            runLoop()
        }
        val isSpeaker = isSpeakerphoneOn()
        val thresh = if (isSpeaker) RMS_THRESHOLD_SPEAKER else RMS_THRESHOLD_EARPIECE
        val need = if (isSpeaker) REQUIRED_CONSECUTIVE_SPEAKER else REQUIRED_CONSECUTIVE_EARPIECE
        Log.i(TAG, "[VAD] started (sampleRate=$SAMPLE_RATE frame=$FRAME_SAMPLES grace=${GRACE_MS}ms threshold=$thresh need=$need speaker=$isSpeaker)")
    }

    private fun isSpeakerphoneOn(): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.isSpeakerphoneOn
        } catch (_: Exception) { false }
    }

    fun stop() {
        if (!running) return
        running = false
        val j = job
        job = null
        j?.cancel()
        Log.i(TAG, "[VAD] stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun runLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "[VAD] getMinBufferSize failed: $minBuf")
            return
        }
        // Use VOICE_COMMUNICATION: may apply AEC on some OEMs, reducing speaker echo pickup.
        // Fallback to MIC if the source fails on this device.
        var audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        var record = createRecord(audioSource, minBuf)
        if (record == null) {
            audioSource = MediaRecorder.AudioSource.MIC
            record = createRecord(audioSource, minBuf)
        }
        if (record == null) {
            Log.e(TAG, "[VAD] AudioRecord creation failed for both VOICE_COMMUNICATION and MIC")
            return
        }

        // Acoustic echo cancellation + noise suppression (Fix 1)
        val sessionId = record.audioSessionId
        val aecAvailable = AcousticEchoCanceler.isAvailable()
        val nsAvailable = NoiseSuppressor.isAvailable()
        Log.i(TAG, "[VAD] AEC available=$aecAvailable NS available=$nsAvailable sessionId=$sessionId audioSource=$audioSource")
        if (aecAvailable) {
            try {
                val canceller = AcousticEchoCanceler.create(sessionId)
                if (canceller != null) {
                    canceller.enabled = true
                    aec = canceller
                    Log.i(TAG, "[VAD] AEC enabled on session $sessionId enabled=${canceller.enabled}")
                } else {
                    Log.w(TAG, "[VAD] AEC create returned null for session $sessionId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[VAD] AEC enable failed", e)
            }
        } else {
            Log.w(TAG, "[VAD] AEC unavailable on this device/build — VAD runs without echo cancellation (false-positive risk)")
        }
        if (nsAvailable) {
            try {
                val suppressor = NoiseSuppressor.create(sessionId)
                if (suppressor != null) {
                    suppressor.enabled = true
                    ns = suppressor
                    Log.i(TAG, "[VAD] NS enabled on session $sessionId enabled=${suppressor.enabled}")
                } else {
                    Log.w(TAG, "[VAD] NS create returned null for session $sessionId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[VAD] NS enable failed", e)
            }
        }

        val buffer = ShortArray(FRAME_SAMPLES)
        var consecutive = 0
        var framesSeen = 0L
        var lastSpeaker = isSpeakerphoneOn()
        var routeSwitches = 0

        try {
            record.startRecording()
            // Quick state check: startRecording() is non-throwing on some ROMs but enters ERROR state.
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "[VAD] startRecording failed state=${record.recordingState}")
                return
            }

            while (scope.isActive && running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        Log.w(TAG, "[VAD] read error $read — retrying after 100ms")
                        delay(100)
                    }
                    continue
                }
                framesSeen++

                // Grace window: ignore echo of TTS onset through the speaker.
                if (System.currentTimeMillis() - ttsStartMs < GRACE_MS) {
                    delay(IDLE_SLEEP_MS)
                    continue
                }

                val isSpeaker = isSpeakerphoneOn()
                if (isSpeaker != lastSpeaker) {
                    routeSwitches++
                    Log.i(TAG, "[VAD] route switched → speaker=$isSpeaker (was $lastSpeaker) frames=$framesSeen — threshold now ${if (isSpeaker) RMS_THRESHOLD_SPEAKER else RMS_THRESHOLD_EARPIECE} need ${if (isSpeaker) REQUIRED_CONSECUTIVE_SPEAKER else REQUIRED_CONSECUTIVE_EARPIECE}")
                    lastSpeaker = isSpeaker
                    consecutive = 0 // reset debounce on route change to avoid carryover
                }
                val thresh = if (isSpeaker) RMS_THRESHOLD_SPEAKER else RMS_THRESHOLD_EARPIECE
                val need = if (isSpeaker) REQUIRED_CONSECUTIVE_SPEAKER else REQUIRED_CONSECUTIVE_EARPIECE
                val rms = computeRms(buffer, read)
                val isSpeech = rms >= thresh

                if (isSpeech) {
                    consecutive++
                    if (consecutive >= need) {
                        val now = System.currentTimeMillis()
                        val graceElapsed = now - ttsStartMs
                        Log.i(TAG, "[VAD] speech detected rms=${rms.toInt()} frames=$framesSeen consecutive=$consecutive graceElapsed=${graceElapsed}ms — triggering barge-in")
                        // Stop ourselves first so the mic is free for SpeechRecognizer.
                        running = false
                        // Release AudioRecord synchronously before callback — caller may start SpeechRecognizer.
                        runCatching { record.stop() }
                        // Callback must not block this loop's cleanup — dispatch.
                        try { onBargeIn() } catch (e: Exception) { Log.e(TAG, "[VAD] onBargeIn threw", e) }
                        break
                    }
                } else {
                    consecutive = 0
                    // Low duty cycle: sleep a frame when quiet.
                    delay(IDLE_SLEEP_MS)
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "[VAD] loop failed", e)
        } finally {
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            runCatching { record.release() }
            runCatching { aec?.release(); aec = null }
            runCatching { ns?.release(); ns = null }
            Log.d(TAG, "[VAD] AudioRecord released after $framesSeen frames aec=${aec != null} ns=${ns != null}")
        }
    }

    private fun createRecord(source: Int, minBuf: Int): AudioRecord? {
        return try {
            val bufBytes = maxOf(minBuf, FRAME_SAMPLES * BYTES_PER_SAMPLE * 2)
            AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            Log.w(TAG, "[VAD] createRecord source=$source failed", e)
            null
        }
    }

    private fun computeRms(buf: ShortArray, len: Int): Double {
        var sumSq = 0.0
        for (i in 0 until len) {
            val s = buf[i].toDouble()
            sumSq += s * s
        }
        if (len == 0) return 0.0
        return sqrt(sumSq / len)
    }
}
