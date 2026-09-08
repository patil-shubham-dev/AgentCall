package com.agentcall.app.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.agentcall.app.call.PiperTtsEngine
import com.agentcall.app.call.SpeechPacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DebugWavExportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "[WAV-EXPORT] launched")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val engine = PiperTtsEngine(this@DebugWavExportActivity)
                val ready = engine.init()
                Log.i(TAG, "[WAV-EXPORT] engine ready=$ready sampleRate=${engine.sampleRate}")
                if (!ready) { finishAndRemoveTask(); return@launch }

                val sentences = listOf(
                    "This is a much longer sentence containing roughly thirty words designed to stress the synthesis pipeline and measure how real time factor scales with input length and phonetic complexity across multiple phrases.",
                    "AgentCall bridges artificial intelligence agents and human judgment through voice calls, enabling clarification, approval, and contextual decision making without requiring constant human monitoring of autonomous workflows.",
                    "When the system detects that a sentence exceeds the maximum word threshold, it splits the utterance on natural clause boundaries such as commas and conjunctions, preserving prosody while ensuring each synthesis chunk remains cancellable within one second."
                )

                val outDir = File(getExternalFilesDir(null), "wav_export")
                outDir.mkdirs()
                // Clean previous
                outDir.listFiles()?.forEach { it.delete() }

                for ((idx, text) in sentences.withIndex()) {
                    val chunks = SpeechPacing.chunkForSynthesis(text)
                    Log.i(TAG, "[WAV-EXPORT] sentence ${idx+1} words=${text.split(Regex("\\s+")).size} chunks=${chunks.size} : ${chunks.map { it.text.take(30) }}")
                    // Concatenate PCM from chunks (sequential to avoid concurrent memory spike)
                    val allSamples = mutableListOf<Float>()
                    var totalSynthMs = 0L
                    for (c in chunks) {
                        val t0 = System.currentTimeMillis()
                        val audio = engine.synthesize(c.text, 1.0f)
                        val dt = System.currentTimeMillis() - t0
                        totalSynthMs += dt
                        if (audio != null) {
                            allSamples.addAll(audio.samples.toList())
                            Log.i(TAG, "[WAV-EXPORT] chunk synthMs=$dt samples=${audio.samples.size} isLast=${c.isLastInSentence}")
                        }
                    }
                    val file = File(outDir, "long_sentence_${idx+1}.wav")
                    writeWav(file, allSamples.toFloatArray(), engine.sampleRate)
                    Log.i(TAG, "[WAV-EXPORT] wrote ${file.absolutePath} bytes=${file.length()} samples=${allSamples.size} totalSynthMs=$totalSynthMs srcLen=${text.length}")
                }

                // Also export one short for comparison
                val shortText = "Hello world. Short test for comparison."
                val shortChunks = SpeechPacing.chunkForSynthesis(shortText)
                val shortSamples = mutableListOf<Float>()
                for (c in shortChunks) {
                    engine.synthesize(c.text, 1.0f)?.let { shortSamples.addAll(it.samples.toList()) }
                }
                val shortFile = File(outDir, "short_sentence.wav")
                writeWav(shortFile, shortSamples.toFloatArray(), engine.sampleRate)
                Log.i(TAG, "[WAV-EXPORT] wrote ${shortFile.absolutePath} bytes=${shortFile.length()}")

                Log.i(TAG, "[WAV-EXPORT] DONE dir=${outDir.absolutePath} files=${outDir.listFiles()?.map { it.name }}")
                Log.i(TAG, "[WAV-EXPORT] PULL: adb pull /sdcard/Android/data/com.agentcall.app/files/wav_export ./wav_export")
            } catch (e: Throwable) {
                Log.e(TAG, "[WAV-EXPORT] failed", e)
            } finally {
                withContext(Dispatchers.Main) { finishAndRemoveTask() }
            }
        }
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        // Convert float [-1,1] to 16-bit PCM
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            pcm[i*2] = (s.toInt() and 0xFF).toByte()
            pcm[i*2+1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + pcm.size)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16) // PCM
            header.putShort(1) // audioFormat PCM
            header.putShort(1) // channels mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2) // byteRate = sampleRate * channels * bits/8
            header.putShort(2) // blockAlign
            header.putShort(16) // bitsPerSample
            header.put("data".toByteArray())
            header.putInt(pcm.size)
            out.write(header.array())
            out.write(pcm)
        }
    }
    companion object { private const val TAG = "AgentCall" }
}
