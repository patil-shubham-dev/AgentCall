package com.agentcall.app.debug

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.agentcall.app.call.BargeInController
import com.agentcall.app.call.PiperTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fix 1 echo test: plays a multi-sentence utterance while VAD is armed.
 * Mode param: "earpiece" or "speaker". With no human speech, barge must NOT fire.
 * Logs: [ECHO-TEST] mode=… vadStarted … playStart … bargeFired= … falsePositive= …
 */
class DebugEchoTestActivity : ComponentActivity() {
    private var bargeFired = false
    private var vadStartMs = 0L
    private var playStartMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "earpiece" // earpiece | speaker | toggle
        val text = intent.getStringExtra("text")
            ?: "This is a very long message designed to span multiple sentences for testing echo cancellation. It keeps going so the system has time to detect false triggers. Please remain silent while the audio plays. The assistant will continue speaking without interruption."

        Log.i(TAG, "[ECHO-TEST] launched mode=$mode textLen=${text.length}")
        lifecycleScope.launch(Dispatchers.IO) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val origSpeaker = try { am.isSpeakerphoneOn } catch (_: Exception) { false }
            try {
                // Set route before playback
                if (mode == "speaker") {
                    am.isSpeakerphoneOn = true
                    // Also try AudioManager.setSpeakerphoneOn via CallAudioManager style? Keep simple.
                    Log.i(TAG, "[ECHO-TEST] set speakerphone ON")
                } else {
                    am.isSpeakerphoneOn = false
                    Log.i(TAG, "[ECHO-TEST] set speakerphone OFF (earpiece)")
                }
                delay(400) // let route settle

                val engine = PiperTtsEngine(this@DebugEchoTestActivity)
                val ready = engine.init()
                Log.i(TAG, "[ECHO-TEST] engine ready=$ready")
                if (!ready) { finishAndRemoveTask(); return@launch }

                bargeFired = false
                val vad = BargeInController(this@DebugEchoTestActivity) {
                    bargeFired = true
                    Log.i(TAG, "[ECHO-TEST] BARGE FIRED at ${System.currentTimeMillis() - vadStartMs}ms after VAD start")
                }
                vadStartMs = System.currentTimeMillis()
                vad.start()
                Log.i(TAG, "[ECHO-TEST] VAD started mode=$mode threshold check")

                // Small delay to let AudioRecord settle, then start playback
                delay(600)
                playStartMs = System.currentTimeMillis()
                Log.i(TAG, "[ECHO-TEST] play start mode=$mode")

                // Synthesize full text as chunks (use engine directly to avoid CallService chunk cap complicating echo test)
                val chunks = com.agentcall.app.call.SpeechPacing.chunkForSynthesis(text)
                Log.i(TAG, "[ECHO-TEST] chunks=${chunks.size} : ${chunks.map { it.text.take(30) }}")
                // For toggle mode, flip speaker mid-playback to test live threshold update
                val toggleAfterChunk = if (mode == "toggle") 2 else -1
                for ((idx, c) in chunks.withIndex()) {
                    if (bargeFired) break
                    if (mode == "toggle" && idx == toggleAfterChunk) {
                        am.isSpeakerphoneOn = true
                        Log.i(TAG, "[ECHO-TEST] TOGGLED speaker ON mid-call at chunk $idx (live threshold should update)")
                        delay(300)
                    }
                    val audio = engine.synthesize(c.text, 1.0f)
                    if (audio == null) continue
                    // Play via Piper's AudioTrack (voice communication) — but we need to ensure it uses current route
                    // Use engine.playAudio directly (it creates AudioTrack with VOICE_COMMUNICATION)
                    val ok = withContext(Dispatchers.Default) { engine.playAudio(audio) }
                    Log.i(TAG, "[ECHO-TEST] played chunk ok=$ok bargeFired=$bargeFired text=\"${c.text.take(40)}\"")
                    if (!c.isLastInSentence) delay(70) else delay(380)
                }

                val elapsed = System.currentTimeMillis() - playStartMs
                delay(800) // grace for late VAD trigger
                val falsePositive = bargeFired
                Log.i(TAG, "[ECHO-TEST] RESULT mode=$mode falsePositive=$falsePositive bargeFired=$bargeFired elapsed=${elapsed}ms chunks=${chunks.size}")

                vad.stop(); vad.release()
                // Small settle before dump
                delay(500)
            } catch (e: Throwable) {
                Log.e(TAG, "[ECHO-TEST] failed", e)
            } finally {
                // Restore speaker
                try { am.isSpeakerphoneOn = origSpeaker } catch (_: Exception) {}
                withContext(Dispatchers.Main) { finishAndRemoveTask() }
            }
        }
    }
    companion object { private const val TAG = "AgentCall" }
}
