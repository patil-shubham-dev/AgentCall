package com.agentcall.app.call

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.agentcall.app.R
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

private const val SAMPLE_RATE = 16000
private const val BARGE_IN_BUFFER_MS = 500
private const val BARGE_IN_THRESHOLD = 3500

@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var signalingClient: SignalingClient

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    var callId: String? = null
    var textToSpeech: TextToSpeech? = null
    var mediaRecorder: MediaRecorder? = null
    var audioFile: File? = null
    var isRecording = false
    var isAiSpeaking = false
    var isPaused = false

    private var audioRecord: AudioRecord? = null
    private var bargeInJob: Job? = null
    private var currentEmotion: String = "neutral"
    private var bargeInCallback: ((String) -> Unit)? = null

    private val api: ApiService = ApiClient.create()

    override fun onCreate() {
        super.onCreate()
        initTts()
    }

    private fun initTts() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.US
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isAiSpeaking = true
                        if (utteranceId == null) return
                        val parts = utteranceId.split(":")
                        if (parts.size >= 2) {
                            currentEmotion = parts[1]
                            adjustTtsForEmotion(currentEmotion)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        isAiSpeaking = false
                        if (utteranceId?.startsWith("breathe_") == true) return
                        dispatchToListeners("ai_finished_speaking", null)
                    }

                    override fun onError(utteranceId: String?) {
                        isAiSpeaking = false
                    }
                })
            }
        }
    }

    private fun adjustTtsForEmotion(emotion: String) {
        val tts = textToSpeech ?: return
        when (emotion) {
            "calm" -> { tts.setPitch(0.85f); tts.setSpeechRate(0.8f) }
            "urgent" -> { tts.setPitch(1.25f); tts.setSpeechRate(1.3f) }
            "excited" -> { tts.setPitch(1.3f); tts.setSpeechRate(1.2f) }
            "thoughtful" -> { tts.setPitch(0.9f); tts.setSpeechRate(0.7f) }
            else -> { tts.setPitch(1.0f); tts.setSpeechRate(1.0f) }
        }
    }

    private val listenerMap = mutableMapOf<String, (Bundle?) -> Unit>()

    fun onBargeInDetected(callback: (String) -> Unit) {
        bargeInCallback = callback
    }

    private fun dispatchToListeners(type: String, data: Bundle?) {
        listenerMap[type]?.invoke(data)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                callId = id
                startForeground(NOTIFICATION_ID, createOngoingCallNotification("AI Call"))
                acquireWakeLock()
                startVoiceSession(id)
            }
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_NOT_STICKY
                val emotion = intent.getStringExtra(EXTRA_EMOTION) ?: "neutral"
                speakWithEmotion(text, emotion, "direct")
            }
            ACTION_BARGE_IN -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_NOT_STICKY
                handleBargeInCommand(text)
            }
            ACTION_END_CALL -> {
                endCall()
                stopSelf()
            }
            "com.agentcall.action.SCHEDULE_CALLBACK" -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val delayMin = intent.getStringExtra(EXTRA_TEXT)?.toIntOrNull() ?: 10
                scope.launch {
                    try {
                        api.scheduleCallback(id, mapOf("delay_minutes" to delayMin))
                    } catch (_: Exception) {}
                }
            }
        }
        return START_STICKY
    }

    fun speakText(text: String, emotion: String = "neutral") {
        speakWithEmotion(text, emotion, UUID.randomUUID().toString())
    }

    fun speakWithEmotion(rawText: String, emotion: String, utteranceId: String) {
        val tts = textToSpeech ?: return
        if (isPaused) return

        currentEmotion = emotion
        adjustTtsForEmotion(emotion)

        val cleanText = rawText
            .replace(Regex("\\[(calm|urgent|excited|thoughtful|neutral)\\]", RegexOption.IGNORE_CASE), "")
            .trim()

        val sentences = cleanText.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

        for ((i, sentence) in sentences.withIndex()) {
            if (isPaused) break

            if (Math.random() < 0.15 && emotion != "urgent" && i < sentences.size - 1) {
                val fillers = listOf("um", "uh", "hmm", "well", "so", "actually", "let me think")
                val filler = fillers.random()
                val fillerId = "${utteranceId}_filler_$i"
                tts.speak(filler, TextToSpeech.QUEUE_ADD, null, "breathe_$fillerId")
            }

            val sentId = "${utteranceId}_$i:$emotion"
            tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, sentId)

            if (Math.random() < 0.4 && i < sentences.size - 1) {
                val breathMs = when (emotion) {
                    "urgent" -> 300L
                    "thoughtful" -> 700L
                    else -> 450L
                }
                val breathId = "${utteranceId}_breath_$i"
                tts.playSilentUtterance(breathMs, TextToSpeech.QUEUE_ADD, breathId)
            }

            val pauseMs = when {
                i == sentences.size - 1 -> 600L
                emotion == "urgent" -> 200L
                emotion == "thoughtful" -> 700L
                else -> 350L
            }
            if (pauseMs > 0 && i < sentences.size - 1) {
                tts.playSilentUtterance(pauseMs, TextToSpeech.QUEUE_ADD, "breathe_pause_$i")
            }
        }
    }

    private var bargeInAudioFile: File? = null

    private fun startBargeInDetection() {
        if (bargeInJob?.isActive == true) return
        bargeInJob = scope.launch {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

                if (bufferSize == AudioRecord.ERROR_BAD_VALUE) return@launch

                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4)

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord = null
                    return@launch
                }

                val dir = File(cacheDir, "barge_in")
                dir.mkdirs()
                bargeInAudioFile = File(dir, "barge_${System.currentTimeMillis()}.pcm")
                val raf = RandomAccessFile(bargeInAudioFile, "rw")

                audioRecord?.startRecording()

                val buffer = ShortArray(bufferSize)
                var totalSamples = 0
                var speechStartMs = -1L
                var consecutiveSilenceMs = 0L
                val speechDurationMs = 0L
                var speechDetected = false

                while (isActive && isAiSpeaking && !isPaused) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read <= 0) continue

                    raf.writeShortArray(buffer, 0, read)
                    totalSamples += read

                    val rms = calculateRms(buffer, read)
                    val isSilence = rms < BARGE_IN_THRESHOLD

                    if (!isSilence) {
                        if (speechStartMs == -1L) {
                            speechStartMs = System.currentTimeMillis()
                        }
                        consecutiveSilenceMs = 0
                        speechDetected = true
                    } else {
                        consecutiveSilenceMs += (read.toLong() * 1000 / SAMPLE_RATE)
                    }

                    if (speechDetected && speechStartMs > 0 &&
                        System.currentTimeMillis() - speechStartMs > BARGE_IN_BUFFER_MS &&
                        consecutiveSilenceMs > 400
                    ) {
                        raf.close()
                        isPaused = true

                        audioRecord?.stop()
                        audioRecord?.release()
                        audioRecord = null

                        textToSpeech?.stop()

                        dispatchToListeners("barge_in_started", null)
                        processBargeInAudio()

                        bargeInJob?.cancel()
                        return@launch
                    }
                }

                raf.close()
            } catch (_: Exception) {}
        }
    }

    private fun calculateRms(buffer: ShortArray, read: Int): Double {
        var sum = 0.0
        for (i in 0 until read) {
            sum += buffer[i] * buffer[i]
        }
        return Math.sqrt(sum / read)
    }

    private suspend fun processBargeInAudio() {
        try {
            val pcmFile = bargeInAudioFile ?: return
            if (!pcmFile.exists()) return

            val wavFile = File(cacheDir, "barge_${System.currentTimeMillis()}.wav")
            val pcmData = pcmFile.readBytes()
            pcmFile.delete()

            val wavHeader = createWavHeader(pcmData.size, SAMPLE_RATE)
            wavFile.writeBytes(wavHeader + pcmData)

            val currentCallId = callId
            if (currentCallId == null) { wavFile.delete(); return }

            val requestBody = wavFile.readBytes().toRequestBody("audio/wav".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("audio", wavFile.name, requestBody)
            val response = api.uploadAudio(currentCallId, part)
            wavFile.delete()

            handleBargeInCommand(response.text)

            if (isPaused && !response.text.lowercase().contains("call me back")) {
                isPaused = false
                val currentId = callId
                if (currentId != null) {
                    startVoiceSession(currentId)
                }
            }
        } catch (_: Exception) {}
    }

    fun handleBargeInCommand(text: String) {
        val lower = text.lowercase().trim()
        val currentCallId = callId ?: return

        if (lower.contains("call me back") || lower.contains("later") ||
            lower.contains("not now") || lower.contains("busy") || lower.contains("some time")
        ) {
            val minutes = Regex("""(\d+)\s*(?:min|m)""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 10
            scope.launch {
                try {
                    api.scheduleCallback(currentCallId, mapOf("delay_minutes" to minutes))
                } catch (_: Exception) {}
            }
            speakWithEmotion("Okay, I'll call you back in $minutes minutes.", "calm", "callback_confirm")
            scope.launch { delay(3000); stopSelf() }
            return
        }

        if (lower.contains("wait") || lower.contains("hold on") || lower.contains("stop") ||
            lower.contains("pause") || lower == "no" || lower.startsWith("wait")
        ) {
            speakWithEmotion("Sure, take your time. Just press record when you're ready.", "calm", "wait_confirm")
            isPaused = true
            return
        }

        if (lower.contains("what") || lower.contains("again") || lower.contains("repeat") ||
            lower.contains("explain") || lower.contains("clarify")
        ) {
            speakWithEmotion("Let me rephrase that.", "thoughtful", "rephrase_intro")
            isPaused = false
            return
        }

        if (lower.contains("think") || lower.contains("let me") || lower.contains("moment") ||
            lower.contains("hang on") || lower.contains("sec")
        ) {
            speakWithEmotion("I'll wait.", "calm", "wait_confirm")
            isPaused = true
            return
        }

        dispatchToListeners("user_said", Bundle().apply { putString("text", text) })
    }

    private fun createWavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val header = ByteArray(44)
        "RIFF".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        var pos = 4
        writeInt(header, pos, 36 + dataSize); pos += 4
        "WAVE".forEachIndexed { i, c -> header[pos + i] = c.code.toByte() }; pos += 4
        "fmt ".forEachIndexed { i, c -> header[pos + i] = c.code.toByte() }; pos += 4
        writeInt(header, pos, 16); pos += 4
        writeShort(header, pos, 1); pos += 2
        writeShort(header, pos, channels); pos += 2
        writeInt(header, pos, sampleRate); pos += 4
        writeInt(header, pos, sampleRate * channels * bitsPerSample / 8); pos += 4
        writeShort(header, pos, channels * bitsPerSample / 8); pos += 2
        writeShort(header, pos, bitsPerSample); pos += 2
        "data".forEachIndexed { i, c -> header[pos + i] = c.code.toByte() }; pos += 4
        writeInt(header, pos, dataSize)
        return header
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun stopBargeInDetection() {
        bargeInJob?.cancel()
        bargeInJob = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
    }

    private fun startVoiceSession(callId: String) {
        scope.launch {
            try {
                val call = api.getCall(callId)
                val summary = call.result?.userResponse ?: call.result?.transcriptSummary ?: call.messageCount?.let {
                    "Continuing our conversation."
                } ?: "AI needs your input."
                speakWithEmotion(summary, "calm", "resume")

                launch {
                    signalingClient.events.collect { event ->
                        when (event) {
                            is VoiceBridgeEvent.AiMessage -> {
                                val enriched = event.enrichedJson
                                val emotion = if (enriched.isNotBlank()) {
                                    try {
                                        JSONObject(enriched).optJSONObject("emotion")?.optString("emotion", "neutral") ?: "neutral"
                                    } catch (_: Exception) { "neutral" }
                                } else "neutral"
                                speakText(event.content, emotion)
                                dispatchToListeners("ai_message", Bundle().apply {
                                    putString("text", event.content)
                                    putString("emotion", emotion)
                                })
                            }
                            is VoiceBridgeEvent.CallEnded -> {
                                speakWithEmotion("Call ended.", "neutral", "end")
                                delay(1500); stopSelf()
                            }
                            is VoiceBridgeEvent.CallCancelled -> {
                                speakWithEmotion("Call was cancelled.", "neutral", "cancel")
                                delay(1500); stopSelf()
                            }
                            is VoiceBridgeEvent.Disconnected -> stopSelf()
                            else -> {}
                        }
                    }
                }
            } catch (_: Exception) { stopSelf() }
        }
    }

    private fun startRecording() {
        if (isRecording) return
        try {
            val dir = File(cacheDir, "voicebridge"); dir.mkdirs()
            audioFile = File(dir, "recording_${System.currentTimeMillis()}.wav")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setOutputFile(audioFile?.absolutePath)
                prepare(); start()
            }
            isRecording = true
            updateNotification("Recording...")
        } catch (_: Exception) { isRecording = false }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.apply { stop(); release() }; mediaRecorder = null
            isRecording = false; updateNotification("Processing...")
            val file = audioFile; val currentCallId = callId
            if (file != null && file.exists() && currentCallId != null) {
                scope.launch { uploadAudio(currentCallId, file); file.delete() }
            }
        } catch (_: Exception) { isRecording = false }
    }

    private suspend fun uploadAudio(callId: String, file: File) {
        try {
            val requestBody = file.readBytes().toRequestBody("audio/wav".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("audio", file.name, requestBody)
            val response = api.uploadAudio(callId, part)
            val lower = response.text.lowercase()

            if (lower.contains("call me back") || lower.contains("later") || lower.contains("not now") || lower.contains("busy")) {
                val minutes = Regex("""(\d+)\s*(?:min|m)""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 10
                api.scheduleCallback(callId, mapOf("delay_minutes" to minutes))
                speakWithEmotion("Okay, I'll call you back in $minutes minutes.", "calm", "cb")
                delay(2000); stopSelf()
                return
            }

            if (lower.contains("wait") || lower.contains("hold") || lower.startsWith("no")) {
                speakWithEmotion("Sure, take your time. Press record when ready.", "calm", "wait")
                isPaused = true
                return
            }

            if (lower.contains("what") || lower.contains("again") || lower.contains("repeat")) {
                speakWithEmotion("Let me rephrase that.", "thoughtful", "rep")
                isPaused = false
                return
            }

            if (lower.contains("think") || lower.contains("let me") || lower.contains("moment")) {
                speakWithEmotion("I'll wait.", "calm", "wt")
                isPaused = true
                return
            }

            speakWithEmotion("You said: ${response.text}", "neutral", "echo")
            dispatchToListeners("user_transcript", Bundle().apply { putString("text", response.text) })
        } catch (_: Exception) {
            speakWithEmotion("Sorry, I didn't catch that.", "calm", "err")
        }
    }

    private fun endCall() {
        stopBargeInDetection()
        signalingClient.disconnect()
        textToSpeech?.stop(); textToSpeech?.shutdown(); textToSpeech = null
        releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceBridge:CallLock")
            .apply { acquire(60_000) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createOngoingCallNotification(text: String): Notification {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val endPi = PendingIntent.getService(this, 2,
            Intent(this, CallService::class.java).setAction(ACTION_END_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val emotionColor = when (currentEmotion) {
            "calm" -> Color.parseColor("#22C55E")
            "urgent" -> Color.parseColor("#EF4444")
            "excited" -> Color.parseColor("#F59E0B")
            "thoughtful" -> Color.parseColor("#818CF8")
            else -> Color.parseColor("#6366F1")
        }

        val emotionPrefix = when (currentEmotion) {
            "urgent" -> "\u26A0\uFE0F "
            "excited" -> "\uD83D\uDE04 "
            "thoughtful" -> "\uD83E\uDD14 "
            "calm" -> "\uD83D\uDE0A "
            else -> ""
        }

        val statusEmoji = when {
            text.contains("Recording") -> "\uD83D\uDD0C "
            text.contains("Speaking") -> "\uD83D\uDDE3\uFE0F "
            text.contains("Processing") -> "\uD83D\uDD04 "
            text.contains("Paused") -> "\u23F8\uFE0F "
            else -> ""
        }

        return NotificationCompat.Builder(this, CHANNEL_ONGOING_CALL)
            .setContentTitle("$statusEmoji$emotionPrefix AI Voice Call")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_agent)
            .setColor(emotionColor)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColorized(true)
            .setSilent(true)
            .addAction(R.drawable.ic_missed_call, "End", endPi)
            .setContentIntent(pi).build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, createOngoingCallNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); endCall(); super.onDestroy() }

    companion object {
        const val ACTION_START_CALL = "com.agentcall.action.START_CALL"
        const val ACTION_START_RECORDING = "com.agentcall.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.agentcall.action.STOP_RECORDING"
        const val ACTION_SPEAK = "com.agentcall.action.SPEAK"
        const val ACTION_BARGE_IN = "com.agentcall.action.BARGE_IN"
        const val ACTION_END_CALL = "com.agentcall.action.END_CALL"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_EMOTION = "emotion"
        const val CHANNEL_ONGOING_CALL = "ongoing_call"
        private const val NOTIFICATION_ID = 1001
    }
}
