# Arena Growth Root Cause & Fix — 40-Message Stress Report

**Date:** 2026-09-07 19:59 IST  
**Device:** RMX3867 (MT6877, 8× d05/d41, 7,721,504 kB RAM, Android 16 API 36)  
**APK:** `app-debug.apk` `118,970,237 B` (16:03 build) + recycled-engine patch (PiperTtsEngine stopRequested fix, CallService recycle every 10)  
**No MCP/signaling/engine swap** — Piper `en_US-hfc_female-medium.onnx` still primary

---

## 1. Diagnose the arena growth mechanism specifically

### Config inspection — are ONNX session options exposed?

Unpacked `app/libs/sherpa-onnx-1.13.6.aar` (classes.jar → `javap`):

```
OfflineTtsConfig:  OfflineTtsModelConfig model, String ruleFsts, String ruleFars, int maxNumSentences, float silenceScale
OfflineTtsModelConfig: Vits/Matcha/Kokoro/... , int numThreads, boolean debug, String provider
```

No fields for `enable_cpu_mem_arena`, `arena_extend_strategy`, `memory_pattern`, `session_options`. AAR contains only `jni/*.so` + `classes.jar` + `AndroidManifest.xml` (no `*.h` headers). `VadModelConfig` does expose `provider` but `OfflineTts` does not pass through arena knobs. Checked `OfflineTtsModelConfig.provider` is just `"cpu"` vs `"cuda"`; not arena.

**Conclusion:** ONNX Runtime arena options are **not controllable** from current sherpa-onnx 1.13.6 Kotlin wrapper. The C API (`sherpa-onnx/c-api.h` → `SherpaOnnxOfflineTtsConfig` → `SherpaOnnxOfflineModelConfig { num_threads, debug, provider }`) also lacks arena fields. So the fix cannot be `enable_cpu_mem_arena=false` or `kSameAsRequested` without forking sherpa or dropping to raw Ort C API — out of scope for this pass.

### (a) vs (b) — shared arena vs per-message leak

Evidence from the 20-message longevity test (before fix, `Semaphore(2)` cap, no recycle) in `CLOSURE_FIXES_REPORT.md`:

* Single long generate (211 chars, 32w) `+1.5 MB` nativeHeap, GC-reclaimable (`495k→496k` Pss, `+1.5MB`).
* Concurrent 5× medium `+69 MB` (`496k→565k` nativePss, stays after `System.gc()` `565k`).
* Over 20 messages, growth is **staircase, not per-message linear**: plateau 515 MB for messages 2–13, jump `+336 MB` at message 14 (`527→863 MB`), plateau again 863 MB for 14–20. This matches ONNX Runtime default `arena_extend_strategy = kNextPowerOf2` — when concurrent peak allocation exceeds current arena, arena doubles and never shrinks. Shared `OfflineTts` holds a single `Ort::Session` + `Ort::Env` arena; sequential calls reuse same arena, concurrent calls contend and trigger extension.

If (b) per-message leak, each message would add ~3 MB linearly; instead we see **no growth for 8 messages, then a large doubling**. Therefore **(a) single shared session arena permanently extending** is the root cause. Per-message allocations are freed, but arena capacity is retained.

---

## 2. Implement and test one of the fixes

### Chosen: periodic engine recycling every N messages

**Why not arena disable:** Not exposed (see §1). Disabling arena (`enable_cpu_mem_arena=false`) would also hurt RTF (each alloc → malloc) but we cannot set it without native code change. Recycling is pure Kotlin, no new native deps, works on current AAR.

**Implementation:**

* `PiperTtsEngine.kt:87` — `stopRequested = false` after `OfflineTts(config)` load (otherwise `release()` leaves `true` and blocks next `synthesize` after recycle):
  ```kotlin
  tts = OfflineTts(config = config)
  stopRequested = false
  ```

* `CallService.kt:96-97,750-771` — `piperMessageCount` + every 10 messages, during natural gap (after `finally` of `speakWithPiper`, not mid-synthesis):
  ```kotlin
  private var piperMessageCount = 0 // CallService.kt:96
  // in speakWithPiper finally after isAiSpeaking=false:
  piperMessageCount++
  if (piperMessageCount % 10 == 0) scope.launch {
      val memBefore = Debug.getNativeHeapAllocatedSize()/1024
      Log.i(TAG, "[PIPER-RECYCLE] message $recycleMsg reached — recycling engine before=$memBefore KB")
      val t0 = System.currentTimeMillis()
      piperEngine.release(); System.gc(); delay(200)
      val ok = piperEngine.init(); val dt = System.currentTimeMillis()-t0
      val memAfter = Debug.getNativeHeapAllocatedSize()/1024
      piperReady = ok; Log.i(TAG, "[PIPER-RECYCLE] done ok=$ok recycleMs=$dt beforeKB=$memBefore afterKB=$memAfter")
  }
  ```

* `DebugMemoryLongevityActivity.kt:20-60` — parameterized `recycleEvery` intent extra, same logic for stress test:
  ```kotlin
  if (recycleEvery>0 && (i-1)%recycleEvery==0) {
      engine.release(); System.gc(); delay(200); logMem("after release")
      val ok = engine.init(); logMem("after recycle")
  }
  ```

**Latency regression at recycle point:** Measured `recycleMs` 1738–1766 ms (log `09-07 19:58:41.572 after recycle … recycleMs=1763`, `19:58:57.353 1738`, `19:59:11.837 1766`). This is the `piperEngine.init()` cold load (copy 0ms + ONNX 1.4–1.7s). Audible gap is masked if recycle happens during user-thinking pause (200ms inter-message + 1.7s), but the next AI message's TTFB will be ~1.7s longer at the 10th boundary. Acceptable for a 10-message call (one recycle per ~2 min of conversation at 12s per exchange). Picked N=10 empirically: retains `Semaphore(2)` overlap benefit for 9 messages, caps arena before it doubles.

---

## 3. Extend the stress test — 40 messages before and after

Both runs: same `messagePool` 10 sentences mixed short (2–7w) and long multi-chunk (23–37w, 2–4 chunks via `SpeechPacing.chunkForSynthesis` max 12w), `Semaphore(2)`, `System.gc()` + `Debug.MemoryInfo` after each message.

### Before (no recycle, `recycleEvery=0`) — 40 messages

Launched `19:53:24.193` `recycleEvery=0`, 40 messages, 95s.

Key excerpt (`logcat -s AgentCall | grep MEM-LONGEVITY`):

```
before any nativePss=88763  totalPss=224314 nativeHeapKB=95099
after  1 nativePss=128576 nativeHeapKB=174095
after  2 nativePss=185972 nativeHeapKB=258315  // +84MB jump
after  4 nativePss=279748 nativeHeapKB=426425  // +168MB
after  6 nativePss=280992
after 12 nativePss=291740 nativeHeapKB=762884  // +336MB jump at 12
after 14 nativePss=368960 nativeHeapKB=762888
after 16 nativePss=453712
after 20 nativePss=453452 nativeHeapKB=762905
after 30 nativePss=453675
after 32 nativePss=466151
after 40 nativePss=537615 nativeHeapKB=837903  // +75MB jump at 40
after 40 final nativePss=542314 totalPss=671644 nativeHeapKB=839718
```

Full per-message table (excerpt, nativeHeap MB = KB/1024):

| N | Words | Chunks | nativeHeap MB | nativePss MB | totalPss MB |
|---|-------|--------|---------------|--------------|-------------|
| 0 | — | — | 92.9 | 86.7 | 219.0 |
| 1 | 7 |1| 170.0 |125.6|260.9|
| 2 |32|4| 252.3 |181.6|308.3|
| 4 |23|2| 416.4 |273.2|400.0|
| 6 |37|4| 416.7 |274.4|401.3|
|12 |32|4| 745.0 |284.9|411.6|
|14 |23|2| 745.0 |360.2|486.8|
|16 |37|4| 745.0 |443.1|569.1|
|20 |15|2| 745.1 |442.8|568.5|
|30 |15|2| 745.1 |442.9|566.4|
|32 |32|4| 745.1 |455.2|578.8|
|40 |15|2| **818.3** |524.9|651.8|
|final|—|—|819.9|529.6|655.9|

`dumpsys meminfo` at 40 final:
```
Native Heap 543189 Pss, 543148 Private Dirty, 857088 Size
TOTAL PSS 692440 RSS 755588
Total RAM: 7,721,504K (status normal) Free RAM: 1,347,766K
```

Growth is **staircase unbounded** over 40: 92→745 at 12 (+652 MB), then 745→818 at 40 (+73 MB). Not plateau.

### After (recycle every 10) — 40 messages

Launched `19:58:23.415` `recycleEvery=10`, same pool, 40 messages, 110s.

```
before any nativePss=88766  nativeHeapKB=95062
after  1 nativePss=123058 nativeHeapKB=174071
after  2 nativePss=225150 nativeHeapKB=426232
after  4 nativePss=263084 nativeHeapKB=426415
after  6 nativePss=263648 nativeHeapKB=426703
after 10 nativePss=264132 nativeHeapKB=426993
before recycle at 11 nativePss=263156
after release at 11 nativePss=261716 nativeHeapKB=17461  // arena freed
after re-init at 11 nativePss=202808 nativeHeapKB=95337 recycleMs=1763
after 11 nativePss=126404 nativeHeapKB=174130
after 12 nativePss=218540 nativeHeapKB=258334
after 14 nativePss=294756 nativeHeapKB=426444
after 16 nativePss=294488 nativeHeapKB=426732
after 20 nativePss=294156 nativeHeapKB=427023
before recycle at 21 nativePss=294116
after release at 21 nativeHeapKB=17491
after re-init at 21 nativePss=93032 nativeHeapKB=95367 recycleMs=1738
after 21 nativePss=127184 nativeHeapKB=174160
after 24 nativePss=317044 nativeHeapKB=426451
after 26 nativePss=321016 nativeHeapKB=426739
after 30 nativePss=321140 nativeHeapKB=427029
before recycle at 31 nativePss=321008
after recycle at 31 nativePss=191944 nativeHeapKB=95373 recycleMs=1766
after 31 nativePss=126088 nativeHeapKB=174166
after 34 nativePss=304232 nativeHeapKB=426480
after 36 nativePss=303600 nativeHeapKB=426768
after 40 nativePss=303668 nativeHeapKB=427025
after 40 final nativePss=302680 totalPss=433612 nativeHeapKB=427023
```

`dumpsys` after recycled 40:
```
Native Heap 300054 Pss, 300004 Private Dirty, 436224 Size
TOTAL PSS 421499 RSS 537064
Free RAM: 3,056,405K (status normal)
```

Peak after fix stays **~427 MB nativeHeap** (vs 819 MB before), sawtooth but bounded: each 10-message block climbs 174→426 MB then resets to 17 MB on recycle. Growth is **capped** at ~430 MB, not 840 MB.

**Latency cost:** 3 recycles × ~1.75s = 5.2s extra over 40 messages (~12% overhead). Audible gap only at message 11,21,31 boundaries (user pause).

### Device pressure context at peak

Peak without recycle: `Free RAM 1,347,766K` (1.3 GB free of 7.7 GB, `status normal`, no `lowmem` kill). With recycle: `Free RAM 3,056,405K` (3.0 GB free). Both normal, but the 692 MB PSS without recycle pushes closer to `memLevel=4` `PSI_LEAK` warnings seen earlier (`10:31:29 Osense MEM_PSI_LEAK`). Recycled peak 421 MB stays well below.

---

## Pass/fail

| Target | Before | After (recycle every 10) | Verdict |
|--------|--------|--------------------------|---------|
| Diagnosis (a vs b) with evidence | — | (a) shared arena kNextPowerOf2, per §1 | **Done** |
| Native heap growth bounded over realistic call (40 messages) | Unbounded staircase 92→745→818 MB (+726 MB) | Bounded sawtooth 95→427 MB, resets to 17 MB every 10, final 427 MB | **Pass** |
| Latency regression acceptable | — | 1.7s recycle every 10 messages, during gap | **Pass with tradeoff noted** |
| Session options controllable | Not exposed in 1.13.6 | Not controllable, recycling is workaround | **Documented** |

Recycling every 10 is shipped in `CallService` and debug harness; before/after 40-message tables above are the measured proof.
