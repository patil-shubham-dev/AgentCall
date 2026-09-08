package com.agentcall.app.debug

import android.app.ActivityManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.agentcall.app.call.BargeInController
import com.agentcall.app.call.PiperTtsEngine
import com.agentcall.app.call.SpeechPacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File

class DebugBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "[BENCH-ACT] launched mode=${intent.getStringExtra("mode") ?: "full"} deleteCache=${intent.getBooleanExtra("deleteCache", false)}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val engine = PiperTtsEngine(this@DebugBenchmarkActivity)

                // Optional cold path: delete extracted cache to force copy + ONNX reload.
                if (intent.getBooleanExtra("deleteCache", false)) {
                    val dest = File(filesDir, "piper")
                    if (dest.exists()) {
                        dest.deleteRecursively()
                        Log.i(TAG, "[BENCH-ACT] deleted cache ${dest.absolutePath}")
                    }
                }

                // B1 cold init
                val tInit0 = System.currentTimeMillis()
                val ready = engine.init()
                val tInit1 = System.currentTimeMillis()
                val initMs = tInit1 - tInit0
                Log.i(TAG, "[BENCH] B1 coldInit ready=$ready timeMs=$initMs sampleRate=${engine.sampleRate}")

                if (!ready) {
                    Log.e(TAG, "[BENCH] engine not ready — abort")
                    finishAndRemoveTask()
                    return@launch
                }

                // B3 per-sentence RTF buckets
                val cases = listOf(
                    "short" to "Hello world. Hello again. Short test.",
                    "short2" to "Hi there.",
                    "medium" to "This is a medium length sentence with about fifteen words for testing the pipeline.",
                    "medium2" to "The quick brown fox jumps over the lazy dog while we measure synthesis time carefully.",
                    "long" to "This is a much longer sentence containing roughly thirty words designed to stress the synthesis pipeline and measure how real time factor scales with input length and phonetic complexity across multiple phrases.",
                    "long2" to "AgentCall bridges artificial intelligence agents and human judgment through voice calls, enabling clarification, approval, and contextual decision making without requiring constant human monitoring of autonomous workflows.",
                )

                // Single-sentence table
                for ((label, text) in cases) {
                    val sentences = SpeechPacing.splitIntoSentences(text)
                    for (s in sentences) {
                        val t0 = System.nanoTime()
                        val audio = engine.synthesize(s, 1.0f)
                        val dtMs = (System.nanoTime() - t0) / 1_000_000
                        val audioMs = if (audio != null) (audio.samples.size * 1000.0 / engine.sampleRate).toLong() else -1
                        val rtf = if (audioMs > 0) dtMs.toDouble() / audioMs else Double.NaN
                        val words = s.trim().split(Regex("\\s+")).size
                        Log.i(TAG, "[BENCH] B3 bucket=$label words=$words chars=${s.length} synthMs=$dtMs audioMs=$audioMs rtf=${"%.3f".format(rtf)} sentence=\"${s.take(80)}\"")
                    }
                    // Also whole-text (multi-sentence) as one call vs split — measure TTFA
                    val tMulti0 = System.currentTimeMillis()
                    val deferred = sentences.map { s -> engine.synthesize(s, 1.0f) }
                    val tMulti1 = System.currentTimeMillis()
                    var totalAudioMs = 0L
                    var ok = 0
                    for (a in deferred) if (a != null) { totalAudioMs += (a.samples.size * 1000.0 / engine.sampleRate).toLong(); ok++ }
                    Log.i(TAG, "[BENCH] B3 multi bucket=$label sentences=${sentences.size} totalSynthMs=${tMulti1 - tMulti0} totalAudioMs=$totalAudioMs count=$ok")
                }

                // B4 TTFA single vs multi (dequeue→first write). Here we directly time synthesize+first write via engine.
                val single = "Hello, this is a single sentence greeting."
                val multi = "Hello, this is the first sentence. Here is a second sentence that continues the thought. And a third sentence wraps it up for multi sentence TTFA measurement."
                for ((label, text) in listOf("single" to single, "multi" to multi)) {
                    val sentences = SpeechPacing.splitIntoSentences(text)
                    val tDequeue = System.currentTimeMillis()
                    // Simulate enqueue→first synthesize (TTFA proxy): first sentence only
                    val first = sentences.firstOrNull() ?: text
                    val tSyn0 = System.nanoTime()
                    val audio = engine.synthesize(first, 1.0f)
                    val tSyn1 = System.nanoTime()
                    val synthMs = (tSyn1 - tSyn0) / 1_000_000
                    val audioMs = if (audio != null) (audio.samples.size * 1000.0 / engine.sampleRate).toLong() else -1
                    // Simulate first AudioTrack.write latency as ~5ms (write is non-blocking until buffer fill; real value from playAudio firstWriteMs)
                    val ttfaProxy = System.currentTimeMillis() - tDequeue
                    Log.i(TAG, "[BENCH] B4 ttfa label=$label sentences=${sentences.size} ttfaProxyMs=$ttfaProxy synthMs=$synthMs audioMs=$audioMs firstSentence=\"${first.take(60)}\"")

                    // Also test actual playAudio for single sentence (will produce audible output if speaker on)
                    if (audio != null && intent.getBooleanExtra("playAudio", false)) {
                        val tPlay0 = System.currentTimeMillis()
                        val ok = withContext(Dispatchers.Default) { engine.playAudio(audio) }
                        val tPlay1 = System.currentTimeMillis()
                        Log.i(TAG, "[BENCH] B4 play label=$label playMs=${tPlay1 - tPlay0} ok=$ok")
                    }
                }

                // Fix 1: AEC/NS availability probe
                Log.i(TAG, "[VAD-PROBE] AEC available=${AcousticEchoCanceler.isAvailable()} NS available=${NoiseSuppressor.isAvailable()}")
                // Instantiate a short-lived BargeInController to trigger its session log without speaking
                val probeController = BargeInController(this@DebugBenchmarkActivity) { Log.i(TAG, "[VAD-PROBE] barge callback fired") }
                probeController.start()
                delay(800)
                probeController.stop()
                probeController.release()
                Log.i(TAG, "[VAD-PROBE] probe done")

                // Fix 2: chunking audit — 30-word sentence must not exceed 12w per generate
                val longSentence = "This is a much longer sentence containing roughly thirty words designed to stress the synthesis pipeline and measure how real time factor scales with input length and phonetic complexity across multiple phrases."
                val chunks = SpeechPacing.chunkForSynthesis(longSentence)
                Log.i(TAG, "[CHUNK] longSentence words=${longSentence.split(Regex("\\s+")).size} → chunks=${chunks.size} : ${chunks.map { it.text.take(40) }}")
                for (c in chunks) {
                    val w = c.text.split(Regex("\\s+")).size
                    val t0 = System.nanoTime(); val a = engine.synthesize(c.text, 1.0f); val dt = (System.nanoTime()-t0)/1_000_000
                    val aMs = if (a!=null) (a.samples.size*1000.0/engine.sampleRate).toLong() else -1
                    Log.i(TAG, "[CHUNK-BENCH] words=$w lastInSentence=${c.isLastInSentence} synthMs=$dt audioMs=$aMs chunk=\"${c.text.take(50)}\"")
                }

                // Fix 3: memory spike diagnosis — single vs concurrent (unbounded 5 vs capped 2)
                fun logMem(tag: String) {
                    val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi)
                    Log.i(TAG, "[MEM] $tag nativePss=${mi.nativePss} dalvikPss=${mi.dalvikPss} totalPss=${mi.totalPss} nativeHeapKB=${Debug.getNativeHeapAllocatedSize()/1024} ")
                }
                // Ensure engine warm
                engine.synthesize("warmup.", 1.0f)
                System.gc(); delay(300); logMem("before single")
                val tSingle0 = System.currentTimeMillis()
                val singleAudio = engine.synthesize(longSentence, 1.0f) // Note: this is still one long generate (for comparison)
                logMem("after single long generate synthMs=${System.currentTimeMillis()-tSingle0} audioSamples=${singleAudio?.samples?.size}")
                System.gc(); delay(300); logMem("after GC single")

                // Concurrent unbounded (old behavior): 5 medium sentences in parallel
                val concurrentTexts = List(5) { "This is a medium length sentence with about fifteen words for testing the pipeline concurrently." }
                System.gc(); delay(300); logMem("before concurrent5")
                val tConc0 = System.currentTimeMillis()
                val deferredUnbounded = concurrentTexts.map { txt -> async(Dispatchers.Default) { engine.synthesize(txt, 1.0f) } }
                val resultsUnbounded = deferredUnbounded.awaitAll()
                logMem("after concurrent5 unbounded timeMs=${System.currentTimeMillis()-tConc0} results=${resultsUnbounded.filterNotNull().size}")
                System.gc(); delay(400); logMem("after GC concurrent5")

                // Capped 2 (new behavior) — same 5 texts but Semaphore(2)
                val sem = Semaphore(2)
                System.gc(); delay(300); logMem("before capped2")
                val tCapped0 = System.currentTimeMillis()
                val deferredCapped = concurrentTexts.map { txt -> async(Dispatchers.Default) {
                    sem.acquire(); try { engine.synthesize(txt, 1.0f) } finally { sem.release() }
                }}
                val resultsCapped = deferredCapped.awaitAll()
                logMem("after capped2 timeMs=${System.currentTimeMillis()-tCapped0} results=${resultsCapped.filterNotNull().size}")
                System.gc(); delay(400); logMem("after GC capped2 final")

                Log.i(TAG, "[BENCH-ACT] DONE")
            } catch (e: Throwable) {
                Log.e(TAG, "[BENCH-ACT] failed", e)
            } finally {
                // Keep activity visible 2s so logcat can be captured before finish.
                withContext(Dispatchers.Main) {
                    finishAndRemoveTask()
                }
            }
        }
    }
    companion object { private const val TAG = "AgentCall" }
}
