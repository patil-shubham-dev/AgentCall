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
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One long-lived microphone capture owner for speech recognition.
 *
 * The platform SpeechRecognizer opens and destroys a recognition session for
 * every utterance, and several OEM speech services play a start/end cue tone
 * each time — the audible "beep loop" between turns. This engine instead keeps
 * one AudioRecord and one sherpa-onnx streaming recognizer open for the whole
 * listening turn: partial hypotheses arrive while the user speaks, endpointing
 * resets only the decoder stream, and no platform speech service is involved.
 *
 * Model: Apache-2.0 LibriSpeech streaming zipformer2 int8,
 * bundled in assets/asr and extracted to filesDir once on first use.
 *
 * Threading: [start] launches the capture loop on Dispatchers.IO; callbacks
 * fire on that loop. [stopAndWait] blocks until AudioRecord and its AEC/NS
 * effects are released so a following barge-in tap can acquire the mic.
 */
class StreamingAsrEngine(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "AgentCall"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1_600 // 100 ms
        private const val BYTES_PER_SAMPLE = 2
        private const val ASSET_DIR = "asr"
        private const val MODEL_MARKER = ".complete"
        private const val ENCODER = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
        private const val DECODER = "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
        private const val JOINER = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val LEVEL_INTERVAL_MS = 90L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private var job: Job? = null
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var modelDir: File? = null

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var ready = false
    @Volatile private var lastPartial = ""
    @Volatile private var released = false

    val isReady: Boolean get() = ready

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError("record permission missing")
            return
        }
        running = true
        val previous = job
        previous?.cancel()
        val gen = ++generation
        job = scope.launch {
            // Let the old loop release AudioRecord/effects before acquiring
            // them again; otherwise a fast stop->start can fight over the mic.
            previous?.cancelAndJoin()
            if (gen != generation) return@launch
            val initialized = init()
            // A newer start (or stop/release) owns the engine now; leave the
            // shared flags and callbacks to that generation.
            if (gen != generation) return@launch
            if (!initialized) {
                running = false
                onError("recognizer init failed")
                return@launch
            }
            if (!running) return@launch
            runLoop(gen)
        }
    }

    /** Stops capture without waiting; use [stopAndWait] before handing the mic to another owner. */
    fun stop() {
        if (!running) return
        running = false
        generation++
        runCatching { record?.stop() }
        job?.cancel()
    }

    /** Stops capture and waits until AudioRecord + audio effects are released. */
    suspend fun stopAndWait() {
        running = false
        generation++
        runCatching { record?.stop() }
        val current = job
        job = null
        current?.cancelAndJoin()
    }

    fun release() {
        running = false
        generation++
        runCatching { record?.stop() }
        val current = job
        job = null
        val engine = synchronized(stateLock) {
            released = true
            ready = false
            val existing = recognizer
            recognizer = null
            existing
        }
        scope.launch {
            current?.cancelAndJoin()
            runCatching { engine?.release() }
            scope.cancel()
        }
    }

    @Synchronized
    fun init(): Boolean {
        if (ready) return true
        if (released) return false
        return try {
            val dir = extractModelIfNeeded()
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, ENCODER).absolutePath,
                        decoder = File(dir, DECODER).absolutePath,
                        joiner = File(dir, JOINER).absolutePath,
                    ),
                    tokens = File(dir, TOKENS).absolutePath,
                    numThreads = 2,
                    modelType = "zipformer2",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 2.4f,
                        minUtteranceLength = 0f,
                    ),
                    rule2 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = 1.1f,
                        minUtteranceLength = 0f,
                    ),
                    rule3 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 0f,
                        minUtteranceLength = 20f,
                    ),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
            val t0 = System.currentTimeMillis()
            val created = OnlineRecognizer(assetManager = null, config = config)
            // release() may race the cold model load on call teardown. Only
            // publish the native recognizer if the engine is still alive;
            // otherwise free it here so it cannot leak.
            val keep = synchronized(stateLock) {
                if (released) {
                    false
                } else {
                    recognizer = created
                    ready = true
                    true
                }
            }
            if (!keep) {
                runCatching { created.release() }
                return false
            }
            Log.i(TAG, "[ASR] model loaded in ${System.currentTimeMillis() - t0}ms dir=${dir.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "[ASR] init failed", e)
            runCatching { recognizer?.release() }
            recognizer = null
            false
        }
    }

    private fun markStopped(gen: Int) {
        if (gen == generation) running = false
    }

    private suspend fun runLoop(gen: Int) {
        val engine = recognizer ?: return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            markStopped(gen)
            onError("audio buffer unavailable")
            return
        }
        var source = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        var input = createRecord(source, minBuf)
        if (input == null) {
            source = MediaRecorder.AudioSource.MIC
            input = createRecord(source, minBuf)
        }
        if (input == null) {
            markStopped(gen)
            onError("microphone unavailable")
            return
        }
        record = input
        val sessionId = input.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(sessionId)?.also {
                    it.enabled = true
                    aec = it
                }
            }.onFailure { Log.w(TAG, "[ASR] AEC enable failed", it) }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(sessionId)?.also {
                    it.enabled = true
                    ns = it
                }
            }.onFailure { Log.w(TAG, "[ASR] NS enable failed", it) }
        }
        Log.i(
            TAG,
            "[ASR] capture start source=$source aec=${aec != null} ns=${ns != null}",
        )

        val shorts = ShortArray(FRAME_SAMPLES)
        val samples = FloatArray(FRAME_SAMPLES)
        var lastLevelMs = 0L
        try {
            input.startRecording()
            if (input.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                markStopped(gen)
                onError("microphone start failed")
                return
            }
            val decoderStream = engine.createStream()
            stream = decoderStream
            while (scope.isActive && running && gen == generation) {
                val read = input.read(shorts, 0, shorts.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        Log.w(TAG, "[ASR] read error=$read")
                        break
                    }
                    continue
                }
                for (i in 0 until read) samples[i] = shorts[i] / 32768f
                decoderStream.acceptWaveform(samples.copyOf(read), SAMPLE_RATE)
                while (engine.isReady(decoderStream)) engine.decode(decoderStream)

                val text = engine.getResult(decoderStream).text.trim()
                if (text != lastPartial) {
                    lastPartial = text
                    onPartial(text)
                }
                if (engine.isEndpoint(decoderStream)) {
                    val finalText = text
                    engine.reset(decoderStream)
                    lastPartial = ""
                    onPartial("")
                    if (finalText.isNotBlank()) onFinal(finalText)
                }
                val now = System.currentTimeMillis()
                if (now - lastLevelMs >= LEVEL_INTERVAL_MS) {
                    lastLevelMs = now
                    onLevel(levelDb(shorts, read))
                }
            }
        } catch (e: Throwable) {
            if (gen == generation && running) {
                Log.e(TAG, "[ASR] capture loop failed", e)
                onError("capture failed")
            }
        } finally {
            markStopped(gen)
            runCatching { stream?.release() }
            stream = null
            runCatching { input.stop() }
            runCatching { input.release() }
            record = null
            runCatching { aec?.release() }
            aec = null
            runCatching { ns?.release() }
            ns = null
            lastPartial = ""
            Log.i(TAG, "[ASR] capture stopped")
        }
    }

    private fun createRecord(source: Int, minBuf: Int): AudioRecord? {
        return try {
            val bufBytes = maxOf(minBuf, FRAME_SAMPLES * BYTES_PER_SAMPLE * 4)
            AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            Log.w(TAG, "[ASR] createRecord source=$source failed", e)
            null
        }
    }

    private fun extractModelIfNeeded(): File {
        modelDir?.let { return it }
        val dir = File(context.filesDir, ASSET_DIR)
        val required = listOf(ENCODER, DECODER, JOINER, TOKENS)
        // A partial first-run extraction leaves files that "exist" but cannot
        // load; the marker is written only after every asset is copied.
        val complete = File(dir, MODEL_MARKER).exists() && required.all { File(dir, it).exists() }
        if (!complete) {
            val t0 = System.currentTimeMillis()
            Log.i(TAG, "[ASR] extracting model to ${dir.absolutePath}")
            dir.deleteRecursively()
            dir.mkdirs()
            for (name in required) {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            File(dir, MODEL_MARKER).createNewFile()
            Log.i(TAG, "[ASR] model extracted in ${System.currentTimeMillis() - t0}ms")
        }
        modelDir = dir
        return dir
    }

    private fun levelDb(buf: ShortArray, len: Int): Float {
        if (len == 0) return -10f
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[i] / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / len).coerceAtLeast(1e-4)
        return (20.0 * log10(rms)).toFloat().coerceIn(-10f, 0f)
    }
}
