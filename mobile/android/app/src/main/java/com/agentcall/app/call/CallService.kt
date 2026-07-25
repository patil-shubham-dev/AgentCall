package com.agentcall.app.call

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.agentcall.app.R
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

private const val SAMPLE_RATE = 16000
private const val BARGE_IN_BUFFER_MS = 500
private const val BARGE_IN_THRESHOLD = 3500
private data class CommandPattern(
    val keywords: List<String>,
    val matchers: List<Regex> = emptyList(),
    val action: suspend (CallService, String, String) -> Unit,
)

@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var signalingClient: SignalingClient

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    var callId: String? = null
    var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    var isRecording = false
    var isAiSpeaking = false
    var isPaused = false

    // Barge-in detection (when AI is speaking, user interrupts)
    private var audioRecord: AudioRecord? = null
    private var bargeInJob: Job? = null

    // In-memory circular buffer for barge-in PCM detection
    private val bargeInCircularBuffer = ShortArray(SAMPLE_RATE * 3)
    private var bargeInBufferWritePos = 0
    private var bargeInBufferSampleCount = 0

    private var currentEmotion: String = "neutral"
    private var bargeInCallback: ((String) -> Unit)? = null

    // Last AI message text, for the Repeat button
    private var lastAiMessage: String = ""
    private var lastAiEmotion: String = "neutral"

    private var speechRecognizer: SpeechRecognizer? = null

    private val api: ApiService = ApiClient.create()

    // ── Configurable Command Patterns ──────────────
    private val commandPatterns = listOf(
        CommandPattern(
            keywords = listOf("call me back", "later", "not now", "busy", "some time"),
            matchers = listOf(Regex("""(\d+)\s*(?:min|m)""")),
            action = { service, text, callId ->
                val minutes = Regex("""(\d+)\s*(?:min|m)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 10
                service.scheduleCallbackAndEnd(callId, minutes)
            },
        ),
        CommandPattern(
            keywords = listOf("wait", "hold on", "stop", "pause"),
            matchers = listOf(Regex("^no$"), Regex("^wait")),
            action = { service, _, _ ->
                service.speakWithEmotion("Sure, take your time. Just press record when you're ready.", "calm", "wait_confirm")
                service.isPaused = true
            },
        ),
        CommandPattern(
            keywords = listOf("what", "again", "repeat", "explain", "clarify"),
            action = { service, _, _ ->
                service.speakWithEmotion("Let me rephrase that.", "thoughtful", "rephrase_intro")
                service.isPaused = false
            },
        ),
        CommandPattern(
            keywords = listOf("think", "let me", "moment", "hang on", "sec"),
            action = { service, _, _ ->
                service.speakWithEmotion("I'll wait.", "calm", "wait_confirm")
                service.isPaused = true
            },
        ),
    )

    private suspend fun scheduleCallbackAndEnd(callId: String, minutes: Int) {
        try {
            api.scheduleCallback(callId, mapOf("delay_minutes" to minutes))
        } catch (_: Exception) {}
        speakWithEmotion("Okay, I'll call you back in $minutes minutes.", "calm", "callback_confirm")
        delay(3000)
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        initTts()
    }

    private fun initTts() {
        if (ttsInitialized && textToSpeech != null) return
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
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
                startForeground(NOTIFICATION_ID_ONGOING, createOngoingCallNotification("AI Call"))
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
            ACTION_REPEAT_LAST -> {
                if (lastAiMessage.isNotBlank()) {
                    speakText(lastAiMessage, lastAiEmotion)
                }
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
        val tts = textToSpeech ?: run { initTts(); return }
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

    // ── Optimized Barge-in Detection (in-memory circular buffer) ──

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

                // Reset circular buffer
                bargeInBufferWritePos = 0
                bargeInBufferSampleCount = 0

                audioRecord?.startRecording()

                val buffer = ShortArray(bufferSize)
                var speechStartMs = -1L
                var consecutiveSilenceMs = 0L
                var speechDetected = false
                val startTime = System.currentTimeMillis()

                while (isActive && isAiSpeaking && !isPaused) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read <= 0) continue

                    // Write to circular buffer (in-memory, no disk I/O)
                    writeToCircularBuffer(buffer, read)

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
                        isPaused = true

                        audioRecord?.stop()
                        audioRecord?.release()
                        audioRecord = null

                        textToSpeech?.stop()

                        dispatchToListeners("barge_in_started", null)
                        processBargeInAudioInMemory()

                        bargeInJob?.cancel()
                        return@launch
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun writeToCircularBuffer(buffer: ShortArray, read: Int) {
        val cap = bargeInCircularBuffer.size
        for (i in 0 until read) {
            bargeInCircularBuffer[bargeInBufferWritePos] = buffer[i]
            bargeInBufferWritePos = (bargeInBufferWritePos + 1) % cap
            if (bargeInBufferSampleCount < cap) bargeInBufferSampleCount++
        }
    }

    private suspend fun processBargeInAudioInMemory() {
        val currentCallId = callId ?: return
        try {
            textToSpeech?.stop()
            val text = transcribeWithSpeechRecognizer() ?: return

            handleBargeInCommand(text)
            sendUserTextToBackend(currentCallId, text)

            if (isPaused && !text.lowercase().contains("call me back")) {
                isPaused = false
                startVoiceSession(currentCallId)
            }
        } catch (_: Exception) {}
    }

    private suspend fun transcribeWithSpeechRecognizer(): String? = suspendCancellableCoroutine { cont ->
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            if (recognizer == null) { cont.resume(null); return@suspendCancellableCoroutine }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text != null && text.isNotBlank()) cont.resume(text) else cont.resume(null)
                    recognizer.destroy()
                }
                override fun onError(error: Int) { cont.resume(null); recognizer.destroy() }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)
            cont.invokeOnCancellation { recognizer.destroy() }
        } catch (_: Exception) { cont.resume(null) }
    }

    private fun sendUserTextToBackend(callId: String, text: String) {
        scope.launch {
            try {
                api.sendUserText(callId, mapOf("text" to text))
            } catch (_: Exception) {}
        }
    }

    private fun processUserText(text: String, callId: String) {
        sendUserTextToBackend(callId, text)
        val lower = text.lowercase()
        for (pattern in commandPatterns) {
            val keywordMatch = pattern.keywords.any { lower.contains(it) }
            val regexMatch = pattern.matchers.any { it.containsMatchIn(lower) }
            if (keywordMatch || regexMatch) {
                scope.launch { pattern.action(this, lower, callId) }
                return
            }
        }
        speakWithEmotion("You said: $text", "neutral", "echo")
        dispatchToListeners("user_transcript", Bundle().apply { putString("text", text) })
        CallEventBus.emit(CallEvent.UserMessage(text))
    }

    fun handleBargeInCommand(text: String) {
        val lower = text.lowercase().trim()
        val currentCallId = callId ?: return

        for (pattern in commandPatterns) {
            val keywordMatch = pattern.keywords.any { lower.contains(it) }
            val regexMatch = pattern.matchers.any { it.containsMatchIn(lower) }
            if (keywordMatch || regexMatch) {
                scope.launch { pattern.action(this@CallService, lower, currentCallId) }
                return
            }
        }

        dispatchToListeners("user_said", Bundle().apply { putString("text", text) })
    }

    private fun stopBargeInDetection() {
        bargeInJob?.cancel()
        bargeInJob = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
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
                                lastAiMessage = event.content
                                lastAiEmotion = emotion
                                CallEventBus.emit(CallEvent.AiMessage(event.content, emotion))
                            }
                            is VoiceBridgeEvent.CallEnded -> {
                                CallEventBus.emit(CallEvent.CallEnded)
                                speakWithEmotion("Call ended.", "neutral", "end")
                                delay(1500); stopSelf()
                            }
                            is VoiceBridgeEvent.CallCancelled -> {
                                CallEventBus.emit(CallEvent.CallEnded)
                                speakWithEmotion("Call was cancelled.", "neutral", "cancel")
                                delay(1500); stopSelf()
                            }
                            is VoiceBridgeEvent.Disconnected -> {
                                CallEventBus.emit(CallEvent.CallEnded)
                                stopSelf()
                            }
                            else -> {}
                        }
                    }
                }
            } catch (_: Exception) { stopSelf() }
        }
    }

    private fun startRecording() {
        if (isRecording || speechRecognizer != null) return
        try {
            stopBargeInDetection()

            val recognizer = SpeechRecognizer.createSpeechRecognizer(this) ?: return
            speechRecognizer = recognizer
            isRecording = true
            updateNotification("Recording...")

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = texts?.firstOrNull()
                    isRecording = false
                    recognizer.destroy()
                    if (speechRecognizer == recognizer) speechRecognizer = null
                    val currentCallId = callId
                    if (text != null && text.isNotBlank() && currentCallId != null) {
                        scope.launch { processUserText(text, currentCallId) }
                    } else {
                        updateNotification("Paused")
                    }
                }

                override fun onError(error: Int) {
                    isRecording = false
                    recognizer.destroy()
                    if (speechRecognizer == recognizer) speechRecognizer = null
                    speakWithEmotion("Sorry, I didn't catch that.", "calm", "err")
                    updateNotification("Paused")
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)
        } catch (_: Exception) { isRecording = false; speechRecognizer = null }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        updateNotification("Processing...")
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
    }

    // ── Fixed TTS Shutdown (stop only, don't destroy) ──

    private fun endCall() {
        stopBargeInDetection()
        signalingClient.disconnect()
        textToSpeech?.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
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
            text.contains("Retrying") -> "\uD83D\uDD04 "
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
        mgr.notify(NOTIFICATION_ID_ONGOING, createOngoingCallNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); endCall(); super.onDestroy() }

    private fun calculateRms(buffer: ShortArray, read: Int): Double {
        var sum = 0.0
        for (i in 0 until read) {
            sum += buffer[i] * buffer[i]
        }
        return Math.sqrt(sum / read)
    }

    companion object {
        const val ACTION_START_CALL = "com.agentcall.action.START_CALL"
        const val ACTION_START_RECORDING = "com.agentcall.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.agentcall.action.STOP_RECORDING"
        const val ACTION_SPEAK = "com.agentcall.action.SPEAK"
        const val ACTION_BARGE_IN = "com.agentcall.action.BARGE_IN"
        const val ACTION_REPEAT_LAST = "com.agentcall.action.REPEAT_LAST"
        const val ACTION_END_CALL = "com.agentcall.action.END_CALL"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_EMOTION = "emotion"
        const val CHANNEL_ONGOING_CALL = "ongoing_call"
        const val CHANNEL_INCOMING_CALL = "incoming_call"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CONTEXT_SUMMARY = "context_summary"
        const val EXTRA_PRIORITY = "priority"
        private const val NOTIFICATION_ID_ONGOING = 1001
        private const val NOTIFICATION_ID_INCOMING = 1002

        fun showIncomingCallNotification(context: Context, callId: String, callerName: String, summary: String) {
            val intent = Intent(context, IncomingCallActivity::class.java).apply {
                putExtra("call_id", callId)
                putExtra("caller_name", callerName)
                putExtra("context_summary", summary)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(context, callId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
                .setContentTitle("Incoming AI Call")
                .setContentText(summary.ifBlank { "AI Agent is calling..." })
                .setSmallIcon(R.drawable.ic_agent)
                .setColor(android.graphics.Color.parseColor("#6366F1"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()

            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID_INCOMING, notification)
        }

        fun cancelIncomingNotification(context: Context) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(NOTIFICATION_ID_INCOMING)
        }
    }
}
