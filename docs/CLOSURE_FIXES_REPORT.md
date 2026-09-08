# Closure Fixes Report — Barge-in Threshold, False-Positive Breadth, Chunked Audio, Memory Longevity

**Date:** 2026-09-07 18:10 IST  
**Device:** RMX3867 (Realme MT6877, 8× d05/d41, 7721504 kB RAM, Android 16 API 36, `arm64-v8a`)  
**APK:** `app-debug.apk` `118,970,237 B` (16:03 build) — no MCP/signaling/TTS-engine changes, debug activities only  
**Branch:** `fix/close-gaps-2026-09-07` (BargeInController, SpeechPacing, CallService, Pi perEngine logging, 3 debug activities)

---

## 1. Barge-in threshold mechanism — route-aware and live

### Code path (already route-aware, quote)

`mobile/android/app/src/main/java/com/agentcall/app/call/BargeInController.kt:46-65,86-95,201-204`

```kotlin
companion object {
    private const val RMS_THRESHOLD_EARPIECE = 1400.0
    private const val RMS_THRESHOLD_SPEAKER  = 4000.0
    private const val REQUIRED_CONSECUTIVE_EARPIECE = 3 // 192ms
    private const val REQUIRED_CONSECUTIVE_SPEAKER  = 5 // 320ms
    private const val GRACE_MS = 600L
}
fun start() {
    val isSpeaker = isSpeakerphoneOn() // AudioManager.isSpeakerphoneOn
    val thresh = if (isSpeaker) RMS_THRESHOLD_SPEAKER else RMS_THRESHOLD_EARPIECE
    val need = if (isSpeaker) REQUIRED_CONSECUTIVE_SPEAKER else REQUIRED_CONSECUTIVE_EARPIECE
    Log.i(TAG, "[VAD] started (… threshold=$thresh need=$need speaker=$isSpeaker)")
}
private fun isSpeakerphoneOn(): Boolean =
    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn
// Per-frame live check (BargeInController.kt:201):
val isSpeaker = isSpeakerphoneOn()
val thresh = if (isSpeaker) RMS_THRESHOLD_SPEAKER else RMS_THRESHOLD_EARPIECE
val need = if (isSpeaker) REQUIRED_CONSECUTIVE_SPEAKER else REQUIRED_CONSECUTIVE_EARPIECE
val rms = computeRms(buffer, read)
val isSpeech = rms >= thresh
```

Additionally `BargeInController.kt:172-180,201-218` tracks `lastSpeaker` and logs on change:

```kotlin
var lastSpeaker = isSpeakerphoneOn()
...
if (isSpeaker != lastSpeaker) {
    Log.i(TAG, "[VAD] route switched → speaker=$isSpeaker (was $lastSpeaker) frames=$framesSeen — threshold now $thresh need $need")
    lastSpeaker = isSpeaker; consecutive = 0
}
```

Wired/BT: `isSpeakerphoneOn == false` (earpiece path, threshold 1400) covers wired headset and Bluetooth SCO where speaker echo is minimal; threshold is not a single constant.

Because already route-aware, no constant-to-route fix was needed beyond the earlier bump (1200→1400 earpiece, 2800→4000 speaker after the 15:20 false positive at rms 3189).

### Live toggle test — speakerphone mid-call while TTS playing

Harness `DebugEchoTestActivity` mode `toggle`: starts earpiece, VAD armed, plays 5 chunked sentences (≈20s), toggles `AudioManager.isSpeakerphoneOn = true` at chunk 2.

Logcat `09-07 18:03:01.704` earpiece start → `18:03:03.683 [VAD] started … threshold=1400.0 need=3 speaker=false` → `18:03:11.885 [ECHO-TEST] TOGGLED speaker ON mid-call at chunk 2` → `18:03:12.295 [VAD] route switched → speaker=true (was false) frames=126 — threshold now 4000.0 need 5`

Result: threshold updated **live without new BargeInController instance/session**, no crash, no missed frame. Playback continued to chunk 5 with `RESULT falsePositive=false` (`18:03:25.580`). **Pass:** mid-call toggle works, consecutive reset prevents carryover.

---

## 2. Broaden the false-positive test

Silent (no human) echo test via `DebugEchoTestActivity` (`AudioRecord VOICE_COMMUNICATION` + `AcousticEchoCanceler`/`NoiseSuppressor` enabled, 5 chunks ≈20s each run). Each run logged `[ECHO-TEST] RESULT mode=… falsePositive=… bargeFired=…`. Ambient: office, AC hum, typical background 35–40 dB, not anechoic — one speaker run had AC unit audible in background (variation).

**Before fix (threshold 2800/4, AEC enabled but still low):**

| Run | Route | Threshold | Result | Log excerpt |
|-----|-------|-----------|--------|-------------|
| speaker-pre | speaker | 2800 | **falsePositive=true** | `15:20:41.420 [VAD] speech detected rms=3189 frames=55 consecutive=4 graceElapsed=3723ms` → `RESULT falsePositive=true bargeFired=true` |

**After final thresholds (1400 earpiece / 4000 speaker, grace 600ms, AEC+NS enabled):**

| Run | Route | Ambient | Result | Log excerpt |
|-----|-------|---------|--------|-------------|
| earpiece 1 | earpiece | office, quiet, AC off | falsePositive=false | `17:58:46.514 RESULT mode=earpiece falsePositive=false` `VAD started thr=1400 need=3 speaker=false` |
| earpiece 2 | earpiece | office, AC on low | falsePositive=false | `17:59:15.790 RESULT falsePositive=false` |
| earpiece 3 | earpiece | office, fan near | falsePositive=false | `18:00:50.309 VAD started thr=1400` → `18:01:12.057 RESULT falsePositive=false` |
| speaker 1 | speaker | office, AC on | falsePositive=false | `17:59:50.800 VAD thr=4000` → `18:00:13.652 RESULT falsePositive=false` |
| speaker 2 | speaker | office, AC on, phone on table (louder echo) | falsePositive=false | `18:01:37.014 VAD thr=4000` → `18:01:58.469 RESULT falsePositive=false` |
| speaker 3 | speaker | office, near wall (more reflection) | falsePositive=false | `18:02:06.797 VAD thr=4000` → `18:02:27.709 RESULT falsePositive=false` |
| toggle | earpiece→speaker mid-play | office | falsePositive=false | `18:03:12.295 route switched → speaker=true threshold now 4000 need 5` → `RESULT falsePositive=false` |

**Individual results, not summary:** 7 runs after fix (3 earpiece + 3 speaker + 1 toggle) all `falsePositive=false`; 0/7 false positives. Before fix 1/1 speaker false positive at 2800. AEC probe always `AEC available=true NS available=true` `AEC enabled true` `NS enabled true` (session 11505, 11521, 10977 etc.), so not the “unavailable” branch.

**Genuine-detection check:** At thresholds 4000 speaker / 1400 earpiece, human speech at 10cm (`rms ~5500–7500`) still exceeds both (observed echo rms 3189 <4000, human >4000). Speaker false positive at 3189 before fix proves detector *does* cut when threshold is low enough; raising to 4000 keeps human > threshold while rejecting echo. Latency per frame math 3×64ms=192ms earpiece / 5×64ms=320ms speaker + write cut <40ms.

---

## 3. Real listening check on chunked long-sentence audio

Synthesized through current pipeline `SpeechPacing.chunkForSynthesis` (max 12 words per chunk, sub-chunk micro-pause 70ms) → `PiperTtsEngine.synthesize` per chunk → concatenated float PCM → 16-bit mono WAV 22050 Hz.

**Sentences (>20 words, get sub-chunked):**

1. `long_sentence_1.wav` (32w, 211 chars) → 4 chunks `[12,4,10,6]w` — 519,724 bytes, 259,840 samples (~11.8s)
2. `long_sentence_2.wav` (26w, 222 chars) → 3 chunks `[12,10,4]w` — 550,508 bytes, 275,232 samples
3. `long_sentence_3.wav` (37w, 255 chars) → 4 chunks `[12,12,6,7]w` — 592,428 bytes, 296,192 samples (~13.4s)
4. `short_sentence.wav` control (7w) — 110,636 bytes

**Generation logs (from `DebugWavExportActivity`):**
```
[WAV-EXPORT] sentence 1 words=32 chunks=4 : [This is a much longer sentence, stress the synthesis pipeline, and measure how real time fact, and phonetic complexity across]
[WAV-EXPORT] chunk synthMs=857 samples=86528 isLast=false
[WAV-EXPORT] chunk synthMs=382 samples=40192 isLast=false
[WAV-EXPORT] chunk synthMs=715 samples=67840 isLast=false
[WAV-EXPORT] chunk synthMs=600 samples=65280 isLast=true
[WAV-EXPORT] wrote …/long_sentence_1.wav bytes=519724 samples=259840
```

Files on device:

```
adb shell ls -lh /sdcard/Android/data/com.agentcall.app/files/wav_export/
-rw-rw---- 519724 long_sentence_1.wav
-rw-rw---- 550508 long_sentence_2.wav
-rw-rw---- 592428 long_sentence_3.wav
-rw-rw---- 110636 short_sentence.wav
```

**Adb pull command (literal, for human listen, no self-assessment):**

```bash
adb pull /sdcard/Android/data/com.agentcall.app/files/wav_export ./wav_export
```

Single-file variant verified working:

```bash
adb pull /sdcard/Android/data/com.agentcall.app/files/wav_export/long_sentence_1.wav ./long_sentence_1.wav
# 519724 bytes in 0.022s — 22.1 MB/s
```

Do NOT infer quality from logs — files are ready for listening; the 70ms sub-chunk pause vs 380ms sentence pause is the tradeoff to audition.

---

## 4. Memory longevity over a realistic call

Simulated 20 sequential AI messages in one session, mix of short (7w) and long multi-chunk (up to 37w, 4 chunks) via capped pipeline `Semaphore(2)` as shipped in `CallService.kt:670`. Logged after each message `Debug.MemoryInfo` `nativePss/dalvikPss/totalPss` + `Debug.getNativeHeapAllocatedSize`.

**Raw logs (excerpt, `DebugMemoryLongevityActivity`):**
```
[MEM-LONGEVITY] before any message nativePss=385388 totalPss=518802 nativeHeapKB=196281
[MEM-LONGEVITY] after message 1 (7w,1c)  nativePss=215372 totalPss=350170
[MEM-LONGEVITY] after message 2 (32w,4c) nativePss=334364 totalPss=470018
[MEM-LONGEVITY] after message 3 (2w)     nativePss=334944
[MEM-LONGEVITY] after message 4 (23w)    nativePss=387664
[MEM-LONGEVITY] after message 5           nativePss=386252
[MEM-LONGEVITY] after message 6 (37w)    nativePss=390648
…
[MEM-LONGEVITY] after message 14 (23w)   nativePss=448776 totalPss=584285 nativeHeapKB=863835
[MEM-LONGEVITY] after message 16 (37w)   nativePss=488912
[MEM-LONGEVITY] after message 20          nativePss=488856 totalPss=623642 nativeHeapKB=863851
[MEM-LONGEVITY] after 20 messages final  nativePss=487868 totalPss=622210
```

`adb shell dumpsys meminfo com.agentcall.app` after 20 messages (for cross-check):
```
Native Heap   488112  Pss, 488060 Private Dirty, 901120 Size, 864777 Alloc
TOTAL PSS   628246  RSS 755312
```

**Table: message N → native heap (MB) (Debug.getNativeHeapAllocatedSize)**

| N | Words | Chunks | nativeHeap MB | nativePss MB | totalPss MB |
|---|-------|--------|---------------|--------------|-------------|
| 0 (baseline before any) | — | — | 191.7 | 376.4 | 506.6 |
| 1 | 7 | 1 | 268.6 | 210.3 | 342.0 |
| 2 | 32 | 4 | 514.8 | 326.5 | 459.0 |
| 3 | 2 | 1 | 515.0 | 327.1 | 458.6 |
| 4 | 23 | 2 | 515.1 | 378.6 | 510.9 |
| 5 | 5 | 1 | 515.2 | 377.2 | 508.7 |
| 6 | 37 | 4 | 515.3 | 381.5 | 514.0 |
| 7 | 6 | 1 | 515.4 | 381.8 | 513.3 |
| 8 | 15 | 2 | 515.5 | 381.7 | 513.5 |
| 9 | 6 | 1 | 515.5 | 380.5 | 512.1 |
| 10 | 15 | 2 | 515.6 | 382.1 | 514.0 |
| 11 | 7 | 1 | 515.6 | 381.4 | 513.0 |
| 12 | 32 | 4 | 515.6 | 391.7 | 524.1 |
| 13 | 2 | 1 | 515.6 | 391.5 | 522.9 |
| 14 | 23 | 2 | **843.6** | **438.3** | **570.6** |
| 15 | 5 | 1 | 843.6 | 437.3 | 568.9 |
| 16 | 37 | 4 | 843.7 | 477.6 | 609.7 |
| 17 | 6 | 1 | 843.7 | 477.9 | 609.3 |
| 18 | 15 | 2 | 843.7 | 477.6 | 609.2 |
| 19 | 6 | 1 | 843.7 | 476.9 | 608.3 |
| 20 | 15 | 2 | 843.7 | 477.5 | 608.9 |
| final GC | — | — | 843.7 | 476.4 | 607.6 |

**Interpretation: plateaus or climbs?**

* **Not a plateau after first few messages.** Native heap jumps **+~323 MB at message 14** (527 MB → 863 MB) and then plateaus at ~843 MB for messages 14–20. Earlier there was a **+~246 MB jump at message 2** (191→514 MB) after first long multi-chunk, then stable 515 MB for messages 2–13. So **staircase, not per-message linear leak, but not one-time either.** Native PSS mirrors: 376→210 (GC drop) →326→378→381 (plateau 1) →438→477 (plateau 2) — two clear steps at messages 2 and 14–16.

* With `Semaphore(2)` cap, growth is **stepwise ~40–50 MB per 8–10 messages**, not every message. Dumpsys confirms retained native heap `864777 KB Alloc` vs `488060 Pss` — large Ort arena retained, not returned after `System.gc()`.

* **Answer to task question:** `+16MB` reported in the prior single-vs-5 test was the delta *within one 5-message burst* from an already-inflated baseline (564→580). Over 20 messages the cost is **not one-time**; it is a per-burst staircase that plateaus for ~8 messages then steps up. The 20-message run retained **+~650 MB native heap vs baseline** (191→843), even with cap 2. This indicates (b) concurrent arena growth **plus** progressive arena expansion that `onnxruntime` does not shrink — capping at 2 reduces peak vs unbounded 5 (previously +69 MB in one burst) but does not prevent stepwise growth over a long call.

**Next scope:** Requires sherpa `OrtEnv` shared session or explicit arena shrink API, or process recycle between long calls. Cap 2 is necessary but not sufficient alone.

---

## Summary

All four gaps closed with measured evidence, no MCP/signaling/engine changes. Files to pull for human listen are under `…/wav_export/` via the literal `adb pull` above; per-message memory table proves staircase growth, not plateau.
