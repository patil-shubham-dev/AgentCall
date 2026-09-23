# AgentCall — Codebase & Health Audit (Read-Only)

**Date:** 2026-09-23
**Method:** Static analysis of the working tree at `ff52cad` (main, == origin/main), git history inspection, and cross-reading the existing evidence base (`AUDIT_SYSTEM_GROUND_TRUTH_2026-09-08.md`, `AUDIT_MCP_CALL_FLOW_2026-09-09.md`, `AUDIT_V2_READINESS_2026-09-09.md`, `LIVE_VALIDATION_2026-09-11.md`, `FIX_STALE_DECLINE_RACE_2026-09-12.md`, `BARGE_IN_AND_BENCHMARK_REPORT.md`, `ARENA_GROWTH_FIX_REPORT.md`). No code was changed. No live device was attached this pass — every claim that needs a live run is flagged as such, not guessed.
**Scope note honored:** `backend/src/v2/` was not re-analyzed (dormant, deletion decision pending). Where it intersects hygiene items (e.g. it is mounted at boot regardless), that is noted as a fact, not re-audited.

---

## 1. Codebase state — uncommitted work is the headline risk

### 1.1 The working tree holds the most important mobile fix of 2026-09, uncommitted

`git status --porcelain` — 10 files, all mobile, all one coherent change:

| File | State | What it is |
|---|---|---|
| `AndroidManifest.xml` | modified | FGS type `dataSync → phoneCall`; adds `FOREGROUND_SERVICE_PHONE_CALL` + `SCHEDULE_EXACT_ALARM`; registers `RingTimeoutReceiver` |
| `AgentCallMessagingService.kt` | modified | FCM rings post **directly** (validate → history row → notification → exact alarm). Starts **no** foreground service |
| `SignalingForegroundService.kt` | modified (−88/+~20) | Legacy in-service ring-timeout job retained as a fallback path; ring-timeout primacy moves to the alarm |
| `CallService.kt` | modified | Mic-denied answer now routes through the ring UI instead of crashing; alarm cancel on answer |
| `IncomingCallActivity.kt` | modified | Cancels the exact-alarm timeout on answer/decline/later |
| `CallRepository.kt` | modified | `getCallDetails()` + 2xx-tolerant `cancelCall()` for the receiver |
| `RingTimeoutPolicy.kt`, `RingTimeoutScheduler.kt`, `RingTimeoutReceiver.kt` | **untracked** | New: pure policy + exact allow-while-idle alarm + receiver with server pre-check |
| `RingTimeoutPolicyTest.kt` | **untracked** | Unit tests for the policy |

This is the **2026-09-12 P0 fix** (the app crashed twice in production on backgrounded rings: dataSync FGS quota exhaustion, then phoneCall Telecom requirements, plus a mic-denied `SecurityException` on answer). The comment trail in the diff is explicit that these are the crash fixes.

**This is the exact repeat-risk this audit was asked to check.** The 2026-09-08 audit (§5) root-caused the lost icon-tint fix to precisely this pattern: *"the fix existed only as an ephemeral working-tree state … and was never committed; the theme refactor then changed the resolved color underneath."* Right now the ring leg — the app's core promise — exists only in the working tree. A lost machine, an over-eager `git checkout .`, or an automated refactor pass on stale `main` reverts the app to a build that crashes on backgrounded rings.

Mitigating facts (verified, not assumed):
- The **backend** half of the 09-12 work *is* committed (`7909cc2` fix(cancel): stale timeout-decline must never kill a live call, with its test suite). Only the phone side is floating.
- No stash entries; `main` is not ahead/behind `origin/main` (`0 0`) — the danger is purely the untracked/modified set above.
- The new code is not raw: `RingTimeoutPolicy` is pure and unit-tested (8 assertions), the scheduler degrades gracefully without exact-alarm permission, and the receiver re-checks server status before declining (the stale-decline pre-check from `FIX_STALE_DECLINE_RACE` §5). What's missing is a **live-device pass** (§5 below) and the commit itself.

### 1.2 Branches — unreconciled but safe

- `feat/outgoing-call-voice-first` and `codex/readme-branding-hygiene`: **zero commits** not on `main` — fully merged, stale, safe to delete (their tips are reachable from main; nothing can be silently lost).
- Ten `dependabot/*` remote branches are open (docker node 25/26 bumps, fastify 4→5, zod 3→4, dotenv 16→17, several dev-deps). None merged. They are latently risky rather than actively harmful — notably **fastify 5** and **zod 4** are major bumps that will need code changes, not blind merges.
- No stash, no detached-HEAD work, reflog-relevant history clean (consistent with the 09-08 audit finding).

### 1.3 Docs vs code drift

The drift is mostly **staleness**, not contradiction, but several documents actively lie to a fresh reader:

| Document | Drift |
|---|---|
| `docs/CURRENT_STATE.md` (dated 08-19) | Describes the ring path as FGS-owned (`ringFromEvent` in `SignalingForegroundService`, FCM push "re-routes through `ACTION_RING_FROM_PUSH` → startForegroundService"). The working tree removes exactly that handoff (FCM posts directly, no FGS). If the tree lands, this doc's §2 phone-receipt section is wrong. |
| `docs/AUDIT_MCP_CALL_FLOW_2026-09-09.md` | Same: documents `AgentCallMessagingService → startForegroundService(SignalingForegroundService)` — superseded by the uncommitted rework. Also still lists `cancel_call` as "pending or active" in passing; `MCP_API_SPEC.md` was corrected for that (per `FIX_STALE_DECLINE_RACE` file map) but this audit wasn't. |
| `docs/TECHNICAL_DEBT_REGISTER_v1.md` | **Internally stale in both directions.** TD-02 says `PrimaryDatabase*Repository` is dead code to delete — it is *wired and used* (`index.ts:31-32,142-143`, database mode). TD-31/32 reference `FINAL_` files, `load-test.ts`, `STT_ENABLED/STT_MODEL` — none exist anymore. The register describes the July system, not this one. |
| `docs/REPOSITORY_DOCUMENTATION_STRUCTURE.md` | Proposes a root layout (API_SPEC.md, ARCHITECTURE_BASELINE.md, KNOWN_LIMITATIONS.md at root…) that never existed as described; pure aspiration, never executed. |
| `docs/REPOSITORY_CLEANUP.md` (07-26) | Largely executed since (no `mobile/ios-archived/`, no root `Dockerfile`, no tracked dist) but never marked done. |
| `docs/NEXT_IMPROVEMENTS.md` / `docs/IMPROVEMENT_BACKLOG.md` | Items 1–3 (ENTER key, options chips, user-text retry) and most backlog items **have shipped** — see §3. Kept open with no status update, so a new session would redo finished work. |
| `VERSION.md` | Claims "VoiceBridge v1.0.0", lists **iOS 16+** as a supported platform (the iOS tree was archived out entirely), and a "14 no-op event subscribers" limitation. Meanwhile the Android app is `versionName 2.1 / versionCode 2` and README's badge says 1.0.0. Three version stories, none reconciled. |
| `AGENTS.md` | Stack lists "STUN/TURN: coturn" and implies WebRTC signaling; `infra/coturn/` exists as config but no WebRTC dependency or code path exists anywhere (`ws` is the only realtime dep). The current architecture is WS-signaling + FCM only. |

The documented audit history (09-08 → 09-12) is excellent and current. The **orientation** docs (CURRENT_STATE, BACKLOG, TECH-DEBT, VERSION) are the stale layer.

---

## 2. Junk / dead weight

### Confirmed dead or dead-weight

1. **`backend/src/v2/` (~11 files + 11 test files)** — out of scope by instruction; restating only the standing verdict: recommendation on record is delete-with-tag (`LIVE_VALIDATION_2026-09-11` §8). Fact relevant to hygiene: `registerV2Routes` mounts unconditionally (`index.ts:418`) and the v2 sweeper/verifier timers arm at boot even though nothing in the product calls `/api/v2/*` — dormant but not free.
2. **`docs/*.log`** — four logcat captures (`logcat-live-validation-…`, `logcat-p0-fgs.log`, `logcat-part1-race.log`, `logcat-testrun-…`, ~19 MB on disk). Gitignored per `LIVE_VALIDATION` §9, so not repo bloat — but they sit in the most-browsed docs folder and should move to an ignored artifacts dir.
3. **Stale orientation docs** (§1.3): `TECHNICAL_DEBT_REGISTER_v1.md`, `REPOSITORY_DOCUMENTATION_STRUCTURE.md`, `REPOSITORY_CLEANUP.md`, `NEXT_IMPROVEMENTS.md`, `IMPROVEMENT_BACKLOG.md`, `VERSION.md` iOS row. These actively misdirect future sessions — worse than useless.
4. **Dead code line:** `CallService.speakWithSystemTts` contains `val prev = tts.let { null } // placeholder to keep shape` — a no-op the comment itself admits is a placeholder. Trivial, but it is exactly the kind of thing a later reader burns time on.
5. **`speakRequestedAt` map** grows one entry per system-TTS utterance and is never pruned within a long call (bounded by call length; harmless but pointless allocation).
6. **Local-only dirs** `.lavish/`, `.impeccable/`, `.opencode/`, `androidx/` — all gitignored/untracked; machine clutter, not repo junk.

### APK weight (the only size lever that matters)

Measured inventory:

| Item | Size | Notes |
|---|---|---|
| `assets/piper/en_US-hfc_female-medium.onnx` | 61 MB | The product. Non-negotiable today. |
| `assets/piper/espeak-ng-data/` | **18 MB** | **Full multi-language bundle** — 357 files covering ~100 languages. The engine synthesizes English (en_US voice) only. The dict/voice pairing argument in `PiperTtsEngine`'s header justifies shipping *a* consistent bundle, not the *whole* one. |
| `libs/sherpa-onnx-1.13.6.aar` (repo) | 47 MB on disk | In-APK impact bounded by `abiFilters = arm64-v8a` only — correct already. |
| Debug APK (measured 09-07) | 119 MB | Release: R8 on (`isMinifyEnabled = true`), but **`shrinkResources` is not set** — minor, since assets dominate. |

**Estimate:** release APK ≈ 95–105 MB, almost all of it Piper. Install footprint is worse than the APK: the 81 MB bundle is **copied again** to `filesDir` on first use (+~1.7 s first-call copy, per benchmark B2). Trimming espeak-ng-data to the English set (en_dict, intonations, the `lang/gmw/en*` voices and their dependencies) saves up to **~17 MB APK + ~17 MB on-disk copy**. This is the single biggest size lever, it is config/asset work, and it is verifiable by one Piper init smoke test.

The app is a *calling* app that ships a 100+ MB APK because of TTS. That's a deliberate trade (offline, no voice-install failure mode) and the right one — but the fat dict bundle is not part of the trade.

---

## 3. Half-finished or abandoned features

State verified per item; "my read" distinguishes bad-idea from deprioritized.

1. **Engine v2 (`backend/src/v2/`)** — *abandoned mid-build by deprioritization, not because it was bad.* M1–M3 are real and tested (event log, idempotency, recovery), the transport/tool-binding layer was never built, and nothing calls it. My read: the streaming-TTS premise was solved cheaper on-device (Piper + chunking), so v2's remaining value is design capital (event-log/idempotency patterns), already partially absorbed into v1 (ai-wait persistence, lease ceilings). Delete per the standing recommendation; keeping it "in case" is the complexity, not the finishing.
2. **Multi-language TTS dictionaries** — *half-finished by accident, not decision.* The full espeak bundle shipped (commit `f634ac3`, "Piper TTS espeak-ng language dictionaries") without any multi-language voice or UI. Nothing consumes 95% of those 18 MB. My read: a dependency-merge side effect promoted to a feature commit. Trim it (§2).
3. **Quick-reply `options` chips** — *was* the canonical half-built feature (`NEXT_IMPROVEMENTS` item 2: "data arrives, never rendered"). **It is finished now**: `CallActivity.kt:434` renders `OptionsRow` from `callContext.options`, tap sends through the normal reply path, and `optionsPicked` prevents re-showing. The backlog doc just never recorded it. Not abandoned — completed-but-undocumented.
4. **Silero VAD upgrade path** — *deliberately deferred, documented as one-line swap* (`BargeInController` header). Energy-RMS VAD works (4/4 false-trigger clean). My read: correct call; revisit only if real-voice testing shows false triggers in noise (needs the live session that's still pending — §5).
5. **Sequential per-sentence synthesis** — proposed in the barge-in report as the fix for long-sentence `generate()` blocking barge-in (2–4 s worst case); superseded by 12-word sub-chunking (`SpeechPacing.chunkForSynthesis`, `MAX_WORDS_PER_CHUNK = 12`) which caps the block at ~1 s while keeping synth/play overlap. Correctly left un-implemented; the problem shrank below the cost.
6. **iOS** — archived out of the tree entirely; only the stale `VERSION.md` row pretends otherwise (§1.3). Dead by decision; keep dead unless the product finds a second user.
7. **Debug harnesses** (5 activities under `src/debug/`) — present, used by the documented benchmark/validation procedures, debug-source-set only so they add zero release weight. Not junk; they are the verification tooling.
8. **Per-call ownership checks** — `NEXT_IMPROVEMENTS` item 4 flagged the gap; **shipped** since (`mcp-ownership.test.ts`, `rest-ownership.test.ts` exercise cross-agent 403s). Completed-but-undocumented, like #3.

Pattern worth naming: this repo's recurring failure is not abandoned code — it is **finished work that no doc records** (options chips, ownership, the retry-with-backoff for user texts all shipped silently) combined with **critical fixes that never get committed** (§1.1). The backlog process exists but stopped being updated around 08-12.

---

## 4. Code optimization opportunities

1. **TTS memory recycle-every-10 — WIRED (direct answer to the brief).** `CallService.kt` carries `piperMessageCount`, and after each message's speech completes (`speakWithPiper` finally block, ~line 792–810): increments, and on every 10th message launches `piperEngine.release()` → GC beat → `init()`, logging `[PIPER-RECYCLE] before/after KB`. This is the strategy the 09-08 audit proved on-device in the harness (427→17 MB allocated, 1.6–1.7 s re-init) and flagged as "proven-but-unwired" — **it is now in the production call path.** Two honest caveats: (a) the recycle fires on the *message counter*, right after the 10th message finishes speaking — if the AI's next message is immediate, its `speakPaced` will join the in-flight re-init under `PIPER_WAIT_MS`, and an engine that misses the wait falls back to system TTS for that message (acceptable degradation, but a mid-call voice change is possible); (b) no production-call log capture of `[PIPER-RECYCLE]` exists yet — the wiring is verified by code, its in-call behavior is not yet verified by a live long call.
2. **`runBlocking(Dispatchers.IO)` in `AgentCallMessagingService.onMessageReceived`** — a blocking server-validation fetch inside FCM's dispatch window. Documented as ~1 s typical; but the endpoint it hits is the same Render host, and under a cold start (keep-warm jitter) that fetch can run tens of seconds, blocking the FCM thread (worst case the platform kills the app for not returning). Cheap hardening: wrap the validation fetch in `withTimeoutOrNull(3_000)` and let policy treat null as skip (the server TTL backstops). Small, safe, removes the only unbounded wait on the ring-critical path.
3. **Backend `/api/v1/health` does full `sessionRepo.list()` + `callbackRepo.list()` per call** — but the keep-warm workflow correctly uses the cheap `/health`, so the heavy variant is only hit by the app's foreground poll and humans. Acceptable at this scale; noted so nobody "optimizes" the wrong endpoint.
4. **Compose-side:** no recomposition red flags found — call UI state flows through `CallStateHolder`/`CallEventBus` with discrete state transitions; `IncomingCallActivity` uses `mutableStateOf` fields rather than a state class, which is stylistic, not a perf issue at this size.
5. **Allocations:** per-message `SimpleDateFormat` construction in `CallRepository.saveUserText/saveAiMessage` and per-sentence `Regex("\\s+")` splits in the synth logging path — trivia, listed only for completeness.
6. **Already done well (do not redo):** fallback poll gating + cadence tiers, FGS park/stop on idle, WS call-scoped only, audio focus/wake-lock lifecycle, persisted retry queues for all five POST classes, `RECEIVER_NOT_EXPORTED` collapse, wake-lock with timeout. The battery-audit pass (H2/M3/M4/L4 markers) already harvested the real wins.

---

## 5. Is everything actually working — feature by feature

Legend: **PASS** = verified live on-device per cited report; **UNIT** = unit-tested only; **CODE** = code-verified only, no live evidence; **UNTESTED** = no evidence either way. Everything from the working tree carries the extra caveat that it has never run on a device *at all* yet.

| Feature | Verdict | Basis and caveats |
|---|---|---|
| Ring delivery, backgrounded/deep-Doze/proc-death | **PASS** (old path) | 3/3 live runs, 1.1–2.5 s visible ring, incl. killed process after 58 min Doze (Ground Truth §1). Caveat: that was the **pre-rework** path; the uncommitted direct-ring path has **never run on a device** — only its policy unit tests exist. |
| Ring → answer → live conversation (voice both ways) | **PASS** | LIVE_VALIDATION TEST 1 (answer by tap, AI messages delivered mid-call, 4m34s agent silence survived). |
| Agent connection lifecycle (MCP sessions, heartbeat, abort-on-disconnect) | **PASS** | LIVE_VALIDATION TESTs 1/2/4; lease persistence across restart unit-verified; explicit-delete abort re-confirmed live. |
| Stale-decline race (timeout killing a live call) | **PASS (server) / UNTESTED (client)** | Server gate committed + unit-tested (328 green); the phone-side pre-check lives in the uncommitted `RingTimeoutReceiver` — same never-ran caveat as above. |
| TTS (Piper) synthesis + pacing + chunking | **PASS** (harness) / **CODE** (in-call) | RTF 0.18–0.34, TTFA 0.4–0.8 s warm (Benchmarks B3/B4); chunked pipeline + Semaphore(2) wired in `CallService`. Long-call in-`CallService` behavior incl. recycle timing: no live capture yet. |
| TTS memory (no leak, recycle) | **PASS** (harness) / **WIRED, CODE** (in-call) | 40-message proof in `DebugMemoryLongevityActivity` + `ARENA_GROWTH_FIX_REPORT`; recycle now in `CallService` (§4.1) — unproven in a real call. |
| Barge-in false-trigger (AI's own voice) | **PASS** | 4/4 configs incl. max-volume speaker + mid-playback route toggle (Ground Truth §3, AEC+NS + threshold 1400). |
| Barge-in real human interruption + STT handoff | **UNTESTED** | Requires a human at the mic; instrumented and ready (`[VAD]`, `[BARGE]`, `play cut` logs). Mic-contention (`ERROR_RECOGNIZER_BUSY`) risk still open. |
| Notifications (ring FSI, quiet channel, missed-call) | **PASS / CODE** | FSI correctly implemented + live-observed (LIVE_VALIDATION §7); quiet-hours ring live-seen; missed-call notification code-verified, not recently live-run. |
| FCM token registration / reconciliation | **PASS** | WorkManager pipeline + Render secret-file fix live-confirmed (`fcmOk:true`, notification observed). |
| Quiet hours | **UNIT + CODE** | `QuietHoursWindowTest` green; live silent-ring observed once; edge ranges (cross-midnight) unit-covered. |
| Caller tune, battery-optimization screens, AI-key management | **CODE/UNIT** | `BatteryOptimizationOemTest` green; screens live-drove during audits; caller tune MediaPlayer path not recently exercised live. |
| Callback ("Call back later") | **CODE** | Retry-queued POST + local reminder wired; no recent live validation. |
| Max-call watchdog | **CODE** | Debug-overridable; the wedge-scenario fix has no automated test and no fresh live proof. |
| Settings/Home flows, profile CRUD | **UNIT** | `CallRepositoryDeleteRenameTest`, `AvailabilityPollCadenceTest`; UI flows manually QA'd historically. |
| CI | **RED (staging)** | `Deploy to Staging` fails identically across recent commits — infra/secret issue, tests/lint/build/security green (LIVE_VALIDATION §9). Prod deploys are manual and were working as of 09-11. |

**Cannot be verified without a live device (the short list):** the entire uncommitted ring-leg rework end-to-end; real-voice barge-in latency + STT handoff; mid-call WS kill by Render (the one documented untested failure mode of the FCM-only architecture); battery-unplugged overnight Doze ring; the recycle-in-real-call timing; [PIPER-RECYCLE] log capture.

---

## 6. Dropped functionality worth reconsidering

**Finish/reintegrate — would genuinely simplify:**

1. **Trim, don't finish: espeak-ng bundle (§2/§3.2).** Not a feature to finish, but the current "workaround" (ship all languages) is more complex than the right version (ship English). Straight simplification.
2. **The alarm-based ring timeout (uncommitted) is itself the dropped-thing pattern done right** — it replaces the FGS-owned timeout coroutine whose background-start constraints caused both P0 crashes. Finishing it (live validation + commit) *removes* complexity rather than adding it. Highest-value completion in the repo.
3. **Silero VAD swap** — only if the pending real-voice session shows noise-driven false triggers. Pre-costed: +1.6 MB, one `VadEngine` swap, documented. Otherwise leave dead.

**Better left dead:**

1. **Engine v2.** Finishing it means building the transport/tool layer *and* migrating the phone's entire v1 REST surface (`AUDIT_V2_READINESS` §5.4 sizes this as the largest single workstream). The current v1 workaround (on-device TTS, chunked synthesis, FCM-only idle) is simpler than what completing v2 would require. Its durable ideas have already been cherry-picked into v1. Delete with a tag when the owner decides.
2. **Sequential per-sentence synthesis** — superseded by chunking; reintroducing it would raise TTFA (no overlap) to fix a problem that now caps at ~1 s.
3. **Cross-pod machinery** (TD-16 advisory locks, TD-20 Redis timers, TD-24 WS migration) — solo-service deployment; complexity with no corresponding user.
4. **iOS** — archived deliberately; the code's absence is the correct state.

---

## 7. Speed — current numbers and where it hurts

Estimates are flagged as such; measurements cite their source.

| Metric | Value | Source / basis |
|---|---|---|
| **Backend cold-spin (Render free)** | **20–30 s** when spun down | Render documented + `keep-render-warm.yml` header. **Mitigation active:** GitHub Actions pings `/health` every 5 min; the workflow survives jitter inside the 15-min idle window, but GitHub cron can be *delayed several minutes under load* — a late ping + a ring = worst-case 20–30 s dead air before FCM even fires. |
| **Ring latency (push → visible ring)** | **1.1–2.5 s** measured | Ground Truth §1 (3/3, incl. cold process + Doze). This is the product's core latency and it is good. |
| **Call-connect (ring → live audio)** | **≈ 3–6 s** realistic, human-paced | Composition estimate: push delivery 0.4–1.5 s + user reaction + answer POST ~1–2 s (live: create 17:51:05 → answered 17:51:16 incl. human tap round-trip) + WS connect + greeting synth. Machine-only answer→first-word ≈ 2–3 s. **If Render cold-starts, add 20–30 s** — the single largest tail latency in the product. |
| **TTS time-to-first-audio (warm)** | **0.4–0.8 s** | Measured proxy (Bench B4). Prewarm-at-ring (`ACTION_PREWARM_TTS` fired when the ring posts) usually hides init inside the 60 s ring window, so answer→first word is typically warm. |
| **TTS TTFA (cold engine)** | **3.5–4.5 s** | Bench B2 init 3.1–3.7 s + first synth. Occurs when the user answers within ~4 s of the ring post (prewarm hasn't finished) or a failed init retries. The `PIPER_WAIT_MS` cap then hands the first message to system TTS — audible quality dip on fast answers. |
| **Synthesis throughput** | **RTF 0.18–0.34** (3–5× real time) | Bench B3; barge-in worst case capped ≈ 1 s by 12-word chunks. |
| **App cold start** | **UNMEASURED — estimate 1–3 s to interactive** | Never instrumented as such. Proxy: FCM cold-process wake reached log receipt in +1.5 s (Ground Truth run 3). Not a bottleneck relative to the above; flagging the measurement gap rather than inventing a number. |
| **APK / install size** | **119 MB debug measured; release est. 95–105 MB; +81 MB duplicated to filesDir on first run** | Bench B7 + asset inventory (§2). Install friction is real for a sideloaded app; the on-disk double-store of the model is unavoidable in this design but grows the "app" footprint users see in Settings. |

**Ranked by user-perceived impact:**

1. **Backend cold-spin tail (20–30 s when it bites)** — invisible most days thanks to keep-warm, catastrophic on the day GitHub's cron lags and the agent tries to call. Biggest possible delay, entirely on the critical path of every feature.
2. **Cold-engine TTFA (3.5–4.5 s of silence after answering fast)** — happens on the most human moment there is: answering quickly. The prewarm covers it only when the user hesitates longer than the init.
3. **Ring latency 1.1–2.5 s** — already good; listed to keep it honest against items 1–2 (it only feels bad when #1 has fired).
4. **APK ~100 MB + 81 MB on-disk copy** — one-time cost per install; espeak trim is the cheap win.
5. **App cold start (unmeasured)** — likely fine; needs a one-session measurement to retire the unknown.

---

## Prioritized punch-list

Ordered by impact. **[SAFE]** = can be done without a device. **[DEVICE]** = needs live-device (or human) validation before/after the change.

1. **[DEVICE → then SAFE] Live-validate the 2026-09-12 P0 ring rework, then commit it.** The uncommitted 10-file set is the app's crash fix and the audit's top structural risk. One live pass: backgrounded ring, Doze, answer, decline, timeout, mic-denied answer. Then commit immediately — the 09-08 lost-fix precedent is the documented warning.
2. **[SAFE] Add a bounded timeout to the ring-validation fetch** in `AgentCallMessagingService` (`withTimeoutOrNull(~3s)` around `getCallDetails`) so a Render cold start can't hang FCM's dispatch thread. ~3 lines + policy already treats null as skip.
3. **[SAFE] Trim `espeak-ng-data` to the English set** (~17 MB APK + ~17 MB on-disk copy saved). Verify with one Piper init + synthesis smoke run.
4. **[SAFE] Decide and execute the v2 deletion** (already recommended 09-11): tag `v2-dormant-archive`, delete `backend/src/v2/` + v2 tests + unconditional route mount, keep `docs/v2/`. Owner decision pending, zero device risk.
5. **[SAFE] Reconcile the stale orientation docs** so the next session doesn't re-audit fiction: update/retire `TECHNICAL_DEBT_REGISTER_v1.md` (its TD-02 is now wrong; TD-31/32 items no longer exist), mark shipped items in `NEXT_IMPROVEMENTS`/`IMPROVEMENT_BACKLOG`, fix `VERSION.md` (iOS row, version story), and refresh `CURRENT_STATE.md` §2 to the direct-ring architecture once #1 lands.
6. **[DEVICE] Run the real-voice barge-in session** (interruption latency + STT handoff + `[PIPER-RECYCLE]` capture in one sitting — both are instrumented and waiting on the same human).
7. **[DEVICE] Mid-call WS-kill test** against Render (the one documented untested failure mode of the FCM-only architecture; defines whether in-call `ai_message` needs an FCM/poll fallback).
8. **[SAFE] Micro-cleanup:** delete the `val prev = tts.let { null }` placeholder line, prune `speakRequestedAt` per call, add `shrinkResources` to the release build type, delete the two fully-merged local branches, move `docs/*.log` to an ignored artifacts dir.
9. **[SAFE] Triage the Dependabot set:** batch-merge the dev-deps; schedule the majors (fastify 5, zod 4) as dedicated code-change tasks, not merges.
10. **[SAFE, needs infra access] Fix the red `Deploy to Staging` CI job** (infra/secret issue, pre-existing) so the pipeline is trustworthy before the next deploy.

*End of report. Analysis-only pass; no code, config, or history was modified. The single file created is this report.*
