package com.agentcall.app.debug

import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.agentcall.app.call.PiperTtsEngine
import com.agentcall.app.call.SpeechPacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

class DebugMemoryLongevityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recycleEvery = intent.getIntExtra("recycleEvery", 0) // 0 = no recycle
        val totalMessages = intent.getIntExtra("messages", 40)
        Log.i(TAG, "[MEM-LONGEVITY] launched messages=$totalMessages cap=2 recycleEvery=$recycleEvery")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val engine = PiperTtsEngine(this@DebugMemoryLongevityActivity)
                var ready = engine.init()
                Log.i(TAG, "[MEM-LONGEVITY] engine ready=$ready recycleEvery=$recycleEvery")
                if (!ready) { finishAndRemoveTask(); return@launch }

                fun logMem(label: String) {
                    val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi)
                    val nativeHeapKB = Debug.getNativeHeapAllocatedSize() / 1024
                    // Also log total RAM pressure
                    val runtime = Runtime.getRuntime()
                    val totalMemKB = runtime.totalMemory() / 1024
                    Log.i(TAG, "[MEM-LONGEVITY] $label nativePss=${mi.nativePss} totalPss=${mi.totalPss} nativeHeapKB=$nativeHeapKB dalvikHeapKB=${mi.dalvikPss} totalMemKB=$totalMemKB")
                    return
                }

                // Mix of short and long/multi-chunk sentences (realistic call)
                val messagePool = listOf(
                    "Hello, how can I help you today?",
                    "This is a much longer sentence containing roughly thirty words designed to stress the synthesis pipeline and measure how real time factor scales with input length and phonetic complexity across multiple phrases.",
                    "Short follow-up.",
                    "AgentCall bridges artificial intelligence agents and human judgment through voice calls, enabling clarification, approval, and contextual decision making without requiring constant human monitoring.",
                    "Got it, thanks for clarifying.",
                    "When the system detects that a sentence exceeds the maximum word threshold, it splits the utterance on natural clause boundaries such as commas and conjunctions, preserving prosody while ensuring each synthesis chunk remains cancellable within one second.",
                    "Can you confirm the deployment time?",
                    "The quick brown fox jumps over the lazy dog while we measure synthesis time carefully.",
                    "Sounds good, proceeding with the plan.",
                    "This is a medium length sentence with about fifteen words for testing the pipeline concurrently."
                )

                System.gc(); delay(400); logMem("before any message totalPss baseline")
                // Simulate N sequential AI messages in one call session, each goes through chunk + cap(2) pipeline
                val sem = Semaphore(2)
                for (i in 1..totalMessages) {
                    // Periodic engine recycling during natural gap (not mid-synthesis) — Fix 2 option
                    if (recycleEvery > 0 && i > 1 && (i-1) % recycleEvery == 0) {
                        val tR0 = System.currentTimeMillis()
                        logMem("before recycle at message $i")
                        // Release and recreate — must be outside synthesis
                        engine.release()
                        System.gc(); delay(200)
                        logMem("after release before re-init at $i")
                        val reReady = engine.init()
                        val rDt = System.currentTimeMillis() - tR0
                        logMem("after recycle re-init at $i ready=$reReady recycleMs=$rDt")
                        if (!reReady) { Log.e(TAG, "[MEM-LONGEVITY] recycle re-init failed at $i"); break }
                    }
                    val text = messagePool[(i-1) % messagePool.size]
                    val chunks = SpeechPacing.chunkForSynthesis(text)
                    val t0 = System.currentTimeMillis()
                    // Concurrent capped synthesis per message (as CallService does)
                    val deferred = chunks.map { c ->
                        async(Dispatchers.Default) {
                            sem.acquire(); try { engine.synthesize(c.text, 1.0f) } finally { sem.release() }
                        }
                    }
                    val audios = deferred.awaitAll().filterNotNull()
                    val dt = System.currentTimeMillis() - t0
                    // Simulate playback not needed, just synthesis to measure memory; optionally play first chunk to keep AudioTrack path warm
                    // Log per-message
                    System.gc(); delay(150) // brief GC to see retained
                    logMem("after message $i words=${text.split(Regex("\\s+")).size} chunks=${chunks.size} synthMs=$dt totalSamples=${audios.sumOf { it.samples.size }} recycleEvery=$recycleEvery")
                    // Small inter-message pause like real call (user thinking)
                    delay(200)
                }
                System.gc(); delay(500); logMem("after $totalMessages messages final recycleEvery=$recycleEvery")
                // Also via dumpsys for cross-check (native heap specifically) — caller should also run: adb shell dumpsys meminfo com.agentcall.app | grep -E 'TOTAL|Native Heap'
                Log.i(TAG, "[MEM-LONGEVITY] DONE — run: adb shell dumpsys meminfo com.agentcall.app | grep -E 'TOTAL|Native Heap'")
            } catch (e: Throwable) {
                Log.e(TAG, "[MEM-LONGEVITY] failed", e)
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.Main) { finishAndRemoveTask() }
            }
        }
    }
    companion object { private const val TAG = "AgentCall" }
}
