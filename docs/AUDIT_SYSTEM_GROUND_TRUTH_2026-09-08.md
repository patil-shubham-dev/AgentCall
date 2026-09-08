# AgentCall — Full System Audit (Ground Truth)

**Date:** 2026-09-08, 15:30–18:10 local (UTC+5:30)
**Device:** RMX3867 (Realme), MediaTek MT6877 (Dimensity 900), 8GB, Android 16 (API 36)
**APK:** `com.agentcall.app` v2.1, installed 2026-09-08 14:44 (current working tree)
**Backend:** Render free tier (`agentcall-66ke.onrender.com`), PostgreSQL persistence
**Method:** Live on-device tests via adb + host-side logcat capture + Render MCP driver (`backend/scripts/fcm-render-wake-driver.mjs`). No code was changed during this audit.

---

## Section 1 — FCM Push-to-Wake: **PASS (reliable on this device, 3/3)**

### What was tested
Real end-to-end ring delivery: MCP `create_call` on Render → backend `notifyPhone` → FCM HTTP v1 → device. Three independent runs under true idle, each with a **30+ minute** uninterrupted idle window (spec minimum met in all runs).

### How
- Idle conditions per run: screen off (`mWakefulness=Dozing`), app backgrounded, **deep Doze forced** (`adb shell dumpsys deviceidle force-idle`, verified `mState=IDLE`), no adb or device contact during the window.
- Trigger: host-side detached Node driver held an authenticated MCP session against Render and fired `create_call` on a go-signal file. Server `createdAt` timestamped from the FCM payload; device receipt from logcat `[DIAG]` lines.
- Continuous host-side logcat capture (detached, so no adb needed during idle).
- Shakedown run first (10-min window) to validate the harness before committing to the full protocol (per user directive).

### Evidence (device logcat, exact excerpts)

**Run 1 — backgrounded ~35 min + Doze:**
```
16:05:21.656 [FCM] ring push received callId=ce068644…
16:05:21.656 [DIAG] fcm_received … createdAt=2026-09-08T10:35:20.853Z receivedAtMs=…721656
16:05:21.805 [DIAG] fgs_onStartCommand … isForeground=false
16:05:22.515 [DIAG] get_calls_response … status=pending elapsedMs=705
16:05:22.925 [RING] ringing callId=ce068644…
16:05:23.059 [DIAG] notification_posted … quiet=false elapsedMs=49
```

**Run 2 — backgrounded 31 min + Doze:**
```
16:43:04.989 [FCM] ring push received callId=bc040a0f…
16:43:05.625 [DIAG] get_calls_response … elapsedMs=581
16:43:05.653 [RING] ringing
16:43:05.703 [DIAG] notification_posted … elapsedMs=41
```

**Run 3 — process killed (`adb shell am kill`, verified `pidof` empty) + Doze 58 min:**
```
17:42:38.364 [FCM] enqueue fcm_registration (KEEP)     ← new process 21095 spawning
17:42:38.437 [FCM] ring push received callId=91be7315…
17:42:38.364→.437  (same PID 21095 — FCM started the process cold)
17:42:39.393 [DIAG] get_calls_response … status=pending elapsedMs=854
17:42:39.481 [RING] ringing
```

### Results table

| Run | Condition | Idle time | createdAt → FCM on device | createdAt → ring visible | Delivery path |
|---|---|---|---|---|---|
| 1 | bg + Doze | 35 min | **+0.8s** | **+2.2s** | FCM only (no WS: FGS was stopped) |
| 2 | bg + Doze | 31 min | **+0.4s** | **+1.1s** | FCM only |
| 3 | **process dead** + Doze | 58 min | **+1.5s** | **+2.5s** | FCM only, cold process start |

Did FCM wake the app or did fallback polling? **FCM woke the app in all runs.** In runs 1–2 the FGS/poll was not running at all (parked/stopped — FCM-only idle); in run 3 the process did not exist. The fallback poll never delivered anything; it is not part of the ring path when idle.

### OEM battery state (measured, not assumed)
- `dumpsys deviceidle whitelist` → **`user,com.agentcall.app`** (exempted from Doze restrictions)
- `am get-standby-bucket` → **5 (EXEMPTED)**
- `appops START_FOREGROUND` → allow
- Device was USB-powered during tests (adb requirement). Unplugged behavior may differ (Doze is stricter on battery) — flagged below as unverified.
- The app's own Battery Optimization screen exists and correctly reports the exemption state.

### Verdict
**Reliable on this device under the tested conditions** — including the worst case (killed process after ~1h in deep Doze, 2.5s to visible ring). Numbers, not impressions: worst-case observed end-to-end latency **2.5s**; best **1.1s**.

### Explicit limitations (not papered over)
1. **Firebase-side delivery ground truth not directly verified.** Render's backend logs (`[fcm] ring push delivered`, `fcmMessageId`) are not accessible from this machine, and the Firebase console was not queried. The 0.4–1.5s device-side receipt times strongly imply direct high-priority delivery (not delayed batching), but "Firebase reported delivered vs delayed" needs either Render log export or a one-time `fcm-live-probe.mjs` run with log access. **Inconclusive at the Firebase layer; conclusive at the device layer.**
2. **OEM proprietary layers partially ruled out only.** Stock-Doze exemption is verified. Realme's proprietary app-freeze (Battery → App Power Management) could not be dumped via adb; the app surviving process-death delivery suggests it is not frozen, but the setting's explicit state is unknown.
3. **USB-powered only.** True battery-powered overnight Doze is stricter (App Standby + light Doze deepen). Not tested.
4. One harness flaw found and worked around: the wake driver's `fetch` has **no timeout** (one `create_call` hung silently for 6+ min, consuming a go-signal). Harness issue, not app issue.

---

## Section 2 — Delivery Architecture Audit: **FCM-primary already implemented (verified live)**

### What the code actually does (traced, then confirmed live)

```
create_call (MCP/HTTP)
  └─ pushCallIncoming()                        [service.ts:170-202]
       ├─ deliverViaWs(userId, payload)        ← best-effort, parallel
       └─ sendFcmPush(userId, payload)         ← awaited; high-priority DATA
  └─ notifyPhone() [service.ts:984]            ← used by lifecycle events
       ├─ if call_incoming && fcm.enabled: void sendFcmPush(...)   ← fired FIRST, unconditional
       ├─ WS open? send over WS (returns true)
       └─ else: queue 2 min (QUEUED_NOTIFICATION_TTL_MS), flushed on reconnect
```

**Answer to "what's the first mechanism that actually delivers":** FCM. The comment at `service.ts:990` says "PRIMARY delivery for call_incoming (FCM-only idle model)" — and the code matches: FCM fires *before and regardless of* WS state. The suspicion "architecture still treats WebSocket as primary" is **outdated** — commit `2414f37` (09-02, "FCM-only idle — remove persistent WS/FGS") flipped it.

### Live confirmation
- Ring runs 1–3 above all delivered with **no WS connection at all** (FGS stopped / process dead). First and only delivery mechanism: FCM.
- App-open test: launching MainActivity produces **zero** `[WS]` log lines — the app sits idle with no socket (comment at `SignalingClient.kt:148`: "FCM-only: WS only for duration of answered call").

### The "idle WS for 15+ min then trigger" scenario — obsolete
There is no idle WebSocket anymore. The socket exists **only while a call is active** (`CallService` opens it via `connectIfIdle()` on answer; `endCall()` parks it). The Render idle-kill bug class (283s latency) cannot recur for rings because no ring ever travels a WS. **Residual exposure:** an in-call WS killed by Render mid-conversation. Mitigations in place: OkHttp `pingInterval(60s)`, `readTimeout` 15s (commit `56093c0`), reconnect with backoff, and `shortLivedStreak` parking. **Not live-tested mid-call — flagged as the one remaining untested failure mode of this architecture.**

### FallbackPollCadence behavior (answer to the specific question)
- Tiers: **foreground/ring-in-flight: 10s**, background <5min-since-activity: 60s, deeper background: 60s for 20 polls then **5 min**, Doze: 5 min (skipping 2 of 3 ticks).
- **Worst-case ring latency in practice:** irrelevant when idle — the poll **does not run when idle** (`shouldRunFallbackPoll` returns false when parked; the FGS stops entirely). It exists only as a foreground safety net while a ring is already in flight or the app is open. The 5-min tier only ever applies while the FGS is alive, which by construction means a ring/call is already active.

### Reconnect-on-resume
**Does not reconnect on resume — by design.** `MainActivity.onResume` explicitly does nothing (`// FCM-only idle: onResume no longer restores a connection`). `connectIfIdle()` is called only from `CallService` (on answer). The Home screen polls availability over HTTP (`AvailabilityPollCadence`), not via WS. So: app coming to foreground after days in background shows correct state via HTTP, and only opens a WS when a call starts.

### Recommendation (flagged for decision, not implemented)
**No architecture flip is needed — the architecture the user hypothesized (polling/FCM primary, WS as optimization) is what shipped on 09-02.** One decision worth making explicitly: whether in-call `ai_message` should also have an FCM/poll fallback if the call-scoped WS dies mid-call. Today that path relies on WS reconnect (15s detection + backoff). A mid-call WS-kill live test would tell whether this is acceptable. Everything else should be left alone.

---

## Section 3 — Barge-In: false-trigger **PASS (4/4 autonomous configs)**; human-voice **NOT TESTED (deferred)**

### Important discovery: the code has moved past the audit baseline
The audit brief describes "RMS threshold 1200, no AEC." The installed build has evolved (undocumented in any report):
- Threshold now **1400** (up from 1200)
- Grace now **600ms** (up from 400ms)
- **Hardware AEC + NS are now enabled** on the capture session: `[VAD] AEC enabled on session 16785 enabled=true` / `NS enabled` (`BargeInController` uses `AcousticEchoCanceler`/`NoiseSuppressor` on the `VOICE_COMMUNICATION` AudioRecord, audioSource=7)

The "naive echo stopgap" the audit asked about is **already implemented via platform AEC** — and it works.

### What was tested (autonomous, per user directive)
`DebugEchoTestActivity`: arms the real VAD, then plays real Piper speech through the speaker/mic loop while nobody speaks. If the AI's own voice triggers barge-in, the log shows `[VAD] speech detected` / `BARGE FIRED` / `falsePositive=true`.

| Config | Duration of playback with mic armed | False barge-in |
|---|---|---|
| Earpiece | 20.2s (5 chunks) | **No** |
| Speaker, normal volume | 19.1s | **No** |
| Speaker, **max volume** (stream 0 → 15/15) | 19.3s | **No** |
| **Live earpiece→speaker toggle mid-playback** | 20.0s | **No** |

Across all four runs: **zero** `[VAD] speech detected` events. The AI's own voice — including loud speaker mode and a mid-playback route flip — never crossed threshold.

### Routing question answered
False-trigger risk did **not** change between earpiece and speaker in any run. With AEC enabled on both, the echo path is suppressed at the capture session level before RMS is computed.

### Not tested (per your scheduling directive)
- **Real human interruption latency.** Requires your voice. The baseline report's frame math (200–260ms when audio is writing; up to ~1s worst-case behind a chunk `generate()`; ≤12-word chunks cap the worst case at ~1s) is still **unverified live**. The chunking cap now makes the old "2–4s long-sentence" worst case unlikely (chunks are ≤12 words ≈ ≤1s synth), but that is code inspection, not a measurement.
- **Post-barge STT handoff** (mic contention `ERROR_RECOGNIZER_BUSY` risk flagged in the baseline) — needs the same live session.

### Deliverable answer
False-triggering from the AI's own voice: **not a real observed problem** (4/4 clean, including worst-case loud + route toggle). Real-voice interruption: **unverified — needs a scheduled session with you present**; everything is instrumented (`[VAD] speech detected rms=…`, `[BARGE] …`, `[PIPER] play cut …`) so the measurement will be one quiet logcat capture.

---

## Section 4 — TTS Memory: **no leak; bounded arena working set; recycle strategy works**

### What was tested
`DebugMemoryLongevityActivity`, two 40-message runs (full message pool incl. 30+ word sentences, chunk cap 12 words, concurrency Semaphore(2) — same as `CallService`), plus `dumpsys meminfo` cross-check. Clean process before each run (`force-stop`).

### Run A — no recycle (worst case)
| Point | Total PSS | Native PSS | Native heap allocated |
|---|---|---|---|
| Baseline (engine loaded, 0 msgs) | 224 MB | 88 MB | 93 MB |
| Msg 5 | 435 MB | 315 MB | 426 MB |
| Msg 10 | 433 MB | 314 MB | 427 MB |
| Msg 15 | 495 MB | 377 MB | 763 MB |
| Msg 20 | 512 MB | 393 MB | 763 MB |
| Msg 40 | 610 MB | 504 MB | 745 MB (plateaued) |

- Growth is **not per-message linear**: fast ramp in msgs 1–10 (→427MB), a step around msg 15 (→763MB allocated), then deceleration (msgs 35→40 added +0.5MB). This is **allocator arena growth to a high-water mark**, not a per-utterance leak.
- **Not released to the OS without recycle**: RSS stays ~600MB after the last message.
- Peak measured here: **610MB total PSS / ~630MB RSS** — matches and slightly exceeds the historical 552MB/632MB observation.

### Run B — engine recycle every 10 messages
| Point | Total PSS | Native heap allocated |
|---|---|---|
| Baseline | 224 MB | 93 MB |
| Before recycle #1 (msg 11) | 414 MB | 427 MB |
| Immediately after `release()` | 411 MB | **17 MB** ← arena actually freed |
| After re-init (+1.6s) | 424 MB | 95 MB |
| After recycle #2 → post re-init | **217 MB** | 95 MB |
| Final (40 msgs, 3 recycles) | **364 MB** | 427 MB (only current window) |

- `PiperTtsEngine.release()` → `OfflineTts.release()` genuinely returns the memory (427→17MB allocated). After re-init, PSS returns to baseline (217MB at msg 21 — *below* Run A's msg-10 level).
- Recycle cost: **1.6–1.7s re-init** — viable only at natural call gaps (the current design constraint).
- `dumpsys` cross-check post-run: TOTAL PSS 348MB — accounted, no orphaned native memory.

### Verdict
- **Leak: NO.** The heap plateaus within each engine-lifetime window and `release()` recovers it fully.
- **Root cause (best evidence):** ONNX Runtime's CPU arena grows to the peak concurrent tensor footprint and retains it. Two features drive the size: the 63MB VITS model + espeak data, and concurrent `generate()` calls (cap 2) each allocating intermediate tensors for up to 12-word chunks. It is a large *steady-state working set*, not a leak.
- **Minimum-RAM estimate:** peak working set ≈ 610MB PSS + ~100MB dalvik/graphics + OS overhead. On a 4GB device with typical 2.2–2.5GB free after boot this is survivable but fragile (LMK risk if anything else is open); on 3GB it would be killed regularly. **Safe floor: 4GB with the recycle strategy active; comfortable: 6GB+.** With recycle every ~10 messages (steady 217–364MB), 3–4GB devices become viable. The recycle strategy exists and is proven on-device; wiring it into `CallService`'s natural gaps is a *decision*, not an emergency — flagged, not implemented per audit rules.

---

## Section 5 — Regression Coverage Gap: root cause identified; process fix proposed

### The regression vector (evidence)
1. **The icon code never changed.** `ActionCircle.kt` last commit: `ac1ea7f` (08-19). Its `iconTint` **defaults** to `MaterialTheme.colorScheme.onSurface` — resolved at composition time from the theme.
2. **The theme under it changed.** `c89d016` (09-01, "overhaul theme tokens to paper+ink system") rewrote `Color.kt` (237 lines), `Theme.kt` (109 lines), `Dimens/Shape/Type`. Any consumer relying on the default tint silently changed appearance. `SectionLabel.kt` has the same pattern (`onSurfaceVariant`).
3. **The "fix" left no trace.** No commit mentions it (`git log --grep` for icon/tint/contrast/visibility → unrelated hits only). No report documents it (`FIXES_REPORT.md`, `CLOSURE_FIXES_REPORT.md`, `ARENA_GROWTH_FIX_REPORT.md` — zero icon/tint entries). Reflog is clean (nothing lost). The current working tree's uncommitted diffs to those files are cosmetic (`shadowElevation` param, doc comment) — **not** a tint fix. Conclusion: the fix existed only as an ephemeral working-tree state (or an adb-side hotfix) and was **never committed**; the theme refactor then changed the resolved color underneath, and with no committed artifact + no automated visual check, the regression was invisible until human eyes caught it.

### Why it "should not have been possible to miss" — but was
Verification after UI fixes today = manual PNG drops (`qa-screenshots/`, `verification_screenshots/` — dozens of hand-captured screenshots, all ad-hoc) **in whichever theme the tester happened to be in**. There is no scripted pass, no dark/light matrix, no per-merge gate. `docs/TESTING_GUIDE.md` contains no screenshot/visual-check procedure at all. The gap isn't the fix — it's that "fixed" was never backed by a repeatable artifact.

### Proposal (not implemented, per audit rules)
A pre-merge visual gate, minimal version:
- **Script** (`qa/visual-pass.sh`, adb + Compose UI dumps already proven by existing `qa-screenshots/*.xml` artifacts): navigate the 7 major screens (Home, Settings, Active Call, Incoming Call, AI Connections, Quiet Hours, Call Messages) × **2 themes** (toggle `uiMode` via `adb shell cmd uimode night yes/no`) × capture `screencap` PNGs into a dated folder + capture `uiautomator dump` XMLs (enables text/contrast assertions later without vision).
- **Runtime:** full pass ≈ 3–4 min on-device. **Effort estimate: 1–1.5 days** to build + wire the screen navigation; half a day more to add a contrast heuristic (e.g., compare icon-region pixel variance vs background) if auto-detection is wanted instead of human review of a fixed 14-image contact sheet.
- **Process rule that would have caught the bug:** any commit touching `ui/theme/**` or `ui/composables/**` must attach the 14-image sheet to the PR/commit. The theme refactor would have surfaced the tint change immediately.

---

## Prioritized findings

### Actually broken — needs fixing now
Nothing in the audited paths is functionally broken. Three items are urgent-adjacent:
1. **364 uncommitted changed files**, including the only surviving copies of recent UI polish. The S5 root cause (a fix lost to the working tree) will recur until this lands. Commit or deliberately discard.
2. **Wake driver harness fragility** (`fcm-render-wake-driver.mjs`): no fetch timeout (one run hung 6+ min consuming a go-signal) and no NAME_CONFLICT self-cleanup on restart. Two-line fixes when you next touch scripts; left untouched per audit rules.
3. **FCM registration first-attempt timeout** under Render cold start (21s timeout → retry succeeded in ~10s). Works as-is (WorkManager retry), but a shorter pre-warm at app open would shave ~30s off first-registration after installs. Low priority.

### Unverified — needs a live test before trusting
1. **Real-voice barge-in latency + STT handoff** (S3) — instrumented and ready; needs you at the mic. The old 2–4s worst case is likely capped at ~1s by 12-word chunking, but that is code inspection until measured.
2. **Mid-call WS kill by Render** (S2) — the one failure mode of the FCM-only architecture with no live data. Test: answer a call, drop the WS server-side (or wait for Render idle recycle), measure AI-message recovery time.
3. **Firebase delivery ground truth** (S1) — needs Render log access or a console check to confirm `fcmMessageId` delivery status; device-side evidence is already conclusive.
4. **Battery-powered (unplugged) overnight Doze ring** (S1) — all tests ran USB-powered; OEM stricter power modes when unplugged are the last untested variable.
5. **Non-exempted battery state** — this device is Doze-whitelisted; a typical user who dismisses the battery-optimization prompt has weaker guarantees. One test with the exemption revoked would quantify it.

### Confirmed solid — safe to leave alone
1. **FCM push-to-wake**: 3/3 runs, 1.1–2.5s visible ring, survives process death after 58 min deep Doze. The core product promise works on this device.
2. **FCM-only idle architecture**: no persistent WS, call-scoped socket, foreground-only fallback poll with correct gating — verified in code and live. The suspected "WS-primary" misarchitecture does not exist.
3. **Echo/false-trigger rejection**: hardware AEC+NS + threshold 1400 + 600ms grace — clean in 4/4 configs including loud speaker and live route toggle.
4. **TTS memory**: bounded (no leak), recoverable via `release()` (proven 427→17MB), recycle cadence validated at 1.6s.
5. **Poll cadence implementation** (`FallbackPollCadence`): matches its unit tests; idle tiers unreachable when idle because the service parks — as designed.
