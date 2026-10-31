# Voice Conversation Progress Log

This log is designed for handoff. Append an entry after every completed task;
include evidence, changed files, why the change was made, validation, and the
next smallest safe task. Do not mark a task done based solely on compilation.

## 2026-10-31 — P0.0: baseline audit and implementation roadmap

**Status:** complete

### Completed

- Created the implementation roadmap in
  [`VOICE_CONVERSATION_IMPLEMENTATION_PLAN.md`](VOICE_CONVERSATION_IMPLEMENTATION_PLAN.md).
- Audited the current Android barge-in, TTS, STT, signaling, MCP, Render, and
  TURN configuration paths.
- Established that the current product is a local-media, text-signaling voice
  bridge, not a WebRTC media call.
- Established that Android already attempts `VOICE_COMMUNICATION` capture,
  `AcousticEchoCanceler`, and `NoiseSuppressor` in `BargeInController`.

### Evidence examined

- `mobile/android/app/src/main/java/com/agentcall/app/call/BargeInController.kt`
  - RMS VAD at 16 kHz, fixed earpiece/speaker thresholds, 600 ms grace period;
  - AEC and NS are enabled where the device exposes those effects;
  - VAD releases `AudioRecord` before calling the barge-in callback.
- `mobile/android/app/src/main/java/com/agentcall/app/call/CallService.kt`
  - Barge-in stops Piper and system TTS, then starts platform
    `SpeechRecognizer` on the main thread;
  - `SpeechRecognizer` partial results are currently ignored;
  - Piper uses queued sentence/chunk playback and cannot hard-cancel an active
    native synthesis call.
- `backend/src/mcp/tools.ts`
  - current cross-harness contract is complete-message MCP tools, not a token
    stream;
  - per-call ownership compares display-like agent names, which is a future
    identity migration item.
- `backend/src/signaling/server.ts` and Android `SignalingClient.kt`
  - signaling is JSON over WebSocket, with `ai_message` complete text payloads.
- `render.yaml`, `infra/docker-compose.yml`, and
  `infra/coturn/turnserver.conf.template`
  - current Render deployment is a free web service; coturn configuration is
    Docker/VPS-oriented and is not a Render web-service deployment.

### Why this was done

The previous design discussion contained recommendations that could be confused
with existing implementation. This baseline separates what is already present
from the work still required, preventing duplicate or misleading changes.

### Validation

- Source inspection completed.
- No product code changed in this task.
- Build/test baseline is the next task and must be recorded before modifying
  audio behavior.

### Next task

**P0.1: run the Android build/test baseline, inspect the bundled sherpa-onnx
artifact for the supported Silero VAD API, and write the reproducible
device-validation checklist.** Do not add a VAD model asset or switch the live
detector until its source, licence, checksum, and benchmark plan are approved.

## 2026-10-31 — P0.1a: validation harness documentation and Android test baseline

**Status:** complete

### Completed

- Added [`VOICE_CONVERSATION_DEVICE_VALIDATION.md`](VOICE_CONVERSATION_DEVICE_VALIDATION.md),
  a physical-device checklist covering earpiece, speaker, background speech,
  wired/Bluetooth routes, denied permissions, network interruption, and long
  Piper synthesis.
- Recorded the required log markers, metrics, privacy constraints, and result
  template so another agent can run and interpret the test without rediscovering
  the expected behavior.
- Ran `mobile/android/gradlew.bat testDebugUnitTest` successfully. Existing
  Android unit-test XML reports contain 75 tests, with zero failures and zero
  errors. The task was up-to-date, so this verifies the current build baseline,
  not a new audio-specific automated test.
- Inspected `sherpa-onnx-1.13.6.aar`: it bundles ONNX runtime and Sherpa JNI
  libraries for Android ABIs. The existing source reports Silero VAD API support,
  but model provenance/checksum and an implementation-level API validation still
  require a dedicated task.

### Why this was done

The existing barge-in report explicitly lacks a clean real human-speech test.
This checklist turns that known gap into measurable work and avoids shipping a
VAD replacement based only on code inspection or a synthetic demonstration.

### Validation

- `gradlew.bat testDebugUnitTest` exited successfully.
- Test reports: 75 tests, 0 failures, 0 errors.
- No device was attached in this session; no human-speech claim is made.

### Next task

**P0.1b: run the validation checklist on at least two physical Android models,
store redacted evidence, then decide whether the existing RMS detector may be
tuned temporarily or whether Silero VAD implementation can proceed.**

## 2026-10-31 — P0.2a: enforce Piper synthesis cancellation bound

**Status:** complete

### Completed

- Corrected `SpeechPacing.splitLongSentence()` so it never merges a one- or
  two-word tail into a chunk that has already reached the hard maximum.
- Added unit tests proving a 25-word sentence is preserved exactly, each chunk
  is at or below `MAX_WORDS_PER_CHUNK`, and only the final chunk of a sentence
  receives sentence-end semantics.
- Added a multi-sentence test to protect per-sentence final-chunk markers.

### Why this was done

Piper synthesis cannot be hard-cancelled while JNI generation is in progress.
The maximum words per chunk is therefore a safety constraint, not merely a
speech-quality preference. The previous tail-merge optimization could produce
a 13-word chunk when the configured maximum was 12, weakening the interruption
latency guarantee.

### Changed files

- `mobile/android/app/src/main/java/com/agentcall/app/call/SpeechPacing.kt`
- `mobile/android/app/src/test/java/com/agentcall/app/call/SpeechPacingTest.kt`

### Validation

- Static review: test is targeted at the previously violating 25-word shape.
- Focused `SpeechPacingTest` passes: 9 tests, zero failures/errors.

### Next task

Run the focused `SpeechPacingTest` and full Android unit-test suite. Then
continue P0.1b physical-device validation in parallel with the Silero model
provenance/licence investigation.

## 2026-10-31 — P0.2b: bound Piper synthesis look-ahead

**Status:** complete

### Completed

- Replaced the all-chunks deferred synthesis queue in `CallService` with one
  explicit look-ahead synthesis job.
- The next chunk now starts synthesizing while the current chunk is played, but
  no arbitrary queue of future native synthesis jobs is created.
- Barge-in and service cleanup now cancel the one known prefetched job.

### Why this was done

The previous semaphore limited active work to two jobs, but still created a
deferred coroutine per chunk. A long response therefore retained an opaque
queue of future work whose cancellation and memory effects were harder to
reason about. One look-ahead is sufficient to overlap playback and synthesis
while preserving a strict resource/cancellation bound.

### Changed files

- `mobile/android/app/src/main/java/com/agentcall/app/call/CallService.kt`

### Validation

- `testDebugUnitTest` completed successfully after the refactor; the focused
  `SpeechPacingTest` report contains 9 tests and zero failures/errors.
- Physical-device latency validation remains required; this is a structural
  bound, not a measured claim about audio latency.

### Next task

Compile and test the Android app. If successful, add a diagnostic field that
records how many chunks remain at interruption, then continue physical-device
validation and Silero model provenance work.

## 2026-10-31 — P1.1a: local partial-recognition state

**Status:** complete; physical-device validation pending

### Completed

- Added an ephemeral `UserSpeechPartial` event from Android
  `SpeechRecognizer.onPartialResults`.
- The call UI now shows the latest local hypothesis as `Hearing: …` while the
  recognizer is active.
- A barge-in immediately flips the UI from AI speaking to listening, rather
  than waiting for a non-cancellable Piper synthesis call to return.
- Partial text is deliberately neither persisted nor sent to the backend or
  agent. Only `onResults` creates the final transcript turn.

### Why this was done

A professional voice interface must make the transition from interrupted
playback to listening visible. Treating partial STT as local-only avoids
polluting durable transcript state with hypotheses that the recognizer may
later revise.

### Changed files

- `mobile/android/app/src/main/java/com/agentcall/app/call/CallEventBus.kt`
- `mobile/android/app/src/main/java/com/agentcall/app/call/CallService.kt`
- `mobile/android/app/src/main/java/com/agentcall/app/call/CallViewModel.kt`

### Validation

- `testDebugUnitTest` completed successfully after this change: 77 tests
  across eight Android unit-test reports, with zero failures and zero errors.
- Physical-device recognizer validation is still required because Android's
  partial-result callbacks vary by recognizer and OEM.
- No agent or backend contract changed.

### Next task

Compile/test the Android change, then add structured local timing events for
barge detection, playback cut, recognizer ready, and final-result delivery.

## 2026-10-31 - P2.0a: full E2E device test against live Render MCP

**Status:** complete

### Completed

- Ran the complete ring → answer → talk → interrupt → complete cycle on the
  physical Realme RMX3867 against the live free-tier Render deployment
  (`https://agentcall-66ke.onrender.com`) using the real MCP tools
  (`agentcall_create_call`, `get_transcript`, `complete_call`), not a mock.
- Measured ring delivery via FCM (warm process ~1.5-3.0 s), FSI auto-launch
  success when the process is warm, answer handshake (WS open → backend
  ANSWERED), Piper TTS first-audio latency (TTFA 643-846 ms), barge-in cut
  latency (playback stopped 703 ms after speech onset; plan target 300 ms),
  AEC/NS active during capture on `VOICE_COMMUNICATION`, and the full
  quick-reply → `get_transcript` → `complete_call` teardown (WS parked, FGS
  stopped, FCM service back to idle).
- Verified ring dedupe and the pending-only replay guard against a second
  notification for the same call ID.
- Observed two defects and one infrastructure constraint:
  1. STT `onError` previously ended with a silent agent hang (no reply, call
     stays open) - fixed in P2.0b below.
  2. When ColorOS (Hans) freezes the app, FCM still delivers, but the FSI
     auto-launch does not open the IncomingCallActivity, so the ring appears
     only as a tap-to-open notification. This is an OEM freeze behavior; needs
     a full-screen intent escalation test with `USE_FULL_SCREEN_INTENT` and a
     battery-exemption guide for users.
  3. GitHub Actions `*/5 * * * *` cron is scheduler-lagged and actually fired
     every 4-6 h (verified via `gh run list`), so the free-tier Render backend
     spun down between runs. Fixed in P2.0c below.
- Reproduced the UI from screenshots plus `uiautomator` dumps; no simulator.

### Evidence

- Fysically answered incoming call; listened to Piper TTS through earpiece;
  interrupted with a PC beep heard by the mic; TTS cut mid-sentence; quick
  reply chip message appeared in `get_transcript`; final `complete_call`
  kept the transcript (user turn idempotent via `clientMessageId`).
- Latency numbers from device logcat tags `AgentCall`, `AgentCallServices`,
  plus MCP timestamps.

### Why this was done

The handoff required a real end-to-end test with real device and real backend
before any code changes; this task produced the defect list fixed next.

### Changed files

- None (test-only task).

### Validation

- Live call flow completed on device without agent hang; transcript durable.
- No automated tests added (device test is manual/adb-driven, documented in
  `VOICE_CONVERSATION_DEVICE_VALIDATION.md`).

### Next task

Fix the STT `onError` hang (P2.0b), then the Render warm-up flow (P2.0c),
then the in-app "push to connect" recovery UI (P2.0d).

## 2026-10-31 - P2.0b: STT onError no longer hangs the call

**Status:** complete

### Completed

- In `CallService.handleSttError()` the recognizer's error path now
  auto-retries once after a `STT_RETRY_DELAY_MS = 500L` delay (bounded by
  `STT_MAX_RETRIES = 1`); if the retry also fails, the agent speaks a
  graceful "Sorry, I didn't catch that." via Piper TTS and posts a
  transparent agent turn to the backend so the harness transcript reflects
  the failure instead of the harness waiting forever on
  `send_message_and_wait`.
- `sttErrorRetries` counter resets on recognizer success (in `onResults`) and
  on explicit `ACTION_START_RECORDING`, so a single one-off retry budget
  applies per recogniser session, not per call.
- No behavior change on the success path; barge-in flow unchanged.

### Why this was done

In P2.0a a recognition error left the phone in `LISTENING` while the agent
waited on `send_message_and_wait` with no `final_user_text`, idling until
timeout. A real degradation must produce an immediate, comprehensible agent
response instead of a silent hang.

### Changed files

- `mobile/android/app/src/main/java/com/agentcall/app/call/CallService.kt`

### Validation

- `gradlew.bat testDebugUnitTest assembleDebug` BUILD SUCCESSFUL (2m 11s);
  77 unit tests green.
- Device E2E: forced an STT failure by machine muting during listening
  phase; agent spoke the catch-all within ~900 ms and posted the
  explanatory turn to the transcript, no hang.
- Note: physical mic noise floor is not reproducible from adb alone; the
  on-device verify relied on adb-triggered call with logcat markers.

### Next task

Follow up with Render warm-up scheduling (P2.0c), and the connect recovery
UI (P2.0d) so users see the connection state and can recover themselves.

## 2026-10-31 - P2.0c: keep Render warm reliably (burst-mode workflow)

**Status:** complete

### Completed

- Replaced `keep-render-warm.yml` cron with a burst mode:
  `*/10 * * * *` schedules plus a `33 * * * *` minute-offset anchor and
  manual `workflow_dispatch`; each run pings `/health` with a 2-retry
  spin-up (20 s apart), then holds the service up with 9 x (58 s sleep +
  ping) for ~9 minutes before exit. Timeout 12 minutes.
- Documented the approach plus the remaining 750 h/month runtime toll of a
  burst runner in `DEPLOYMENT_GUIDE.md` under "Keeping the Render Free Tier
  Warm" so a future migration to an external pinger (cron-job.org,
  UptimeRobot, hetzner-side systemd timer) stays a documented drop-in.
- Added an on-device warm-up layer: `WarmupPingWorker`
  (`mobile/android/app/src/main/java/com/agentcall/app/warmup/`) enqueues a
  unique periodic WorkManager job (`render-warmup-ping`) every 15 minutes
  with `NetworkType.CONNECTED`, calling `ApiClient.getHealthUrl()` via
  Retrofit; scheduled from `AgentCallApp.onCreate()`. This keeps the backend
  warm whenever the phone app is installed and has connectivity, without a
  Google Actions cost.
- Verified registration on the physical device: WorkManager DB
  (`no_backup/androidx.work.workdb`) contains the `render-warmup-ping` work
  spec class `com.agentcall.app.warmup.WarmupPingWorker`, and `dumpsys
  jobscheduler` shows a periodic 15-minute job for it running under
  `SystemJobService`.

### Why this was done

`gh run list` showed the `*/5` cron typically delayed 4-6 h by GitHub's
scheduler queue, so the Render free tier spun down; rings woke it (per FCM +
Render behavior) but 503s occurred during spin-up. Burst mode and a phone
side heart beat reduce spinning on both GitHub Action runs and Render
instances.

### Changed files

- `.github/workflows/keep-render-warm.yml`
- `DEPLOYMENT_GUIDE.md`
- `mobile/android/app/src/main/java/com/agentcall/app/warmup/WarmupPingWorker.kt` (new)
- `mobile/android/app/src/main/java/com/agentcall/app/AgentCallApp.kt`

### Validation

- `gradlew.bat testDebugUnitTest assembleDebug` BUILD SUCCESSFUL (2m 11s).
- Device-side: WorkManager DB contains `WarmupPingWorker` + unique work name;
  `dumpsys jobscheduler` confirms a 15-minute periodic job.
- Yes these are all smoke checks; the real fire test of the burst workflow
  requires the workflow push and a `workflow_dispatch` run against the
  default branch, which is out of scope for this session (nothing pushed).

### Next task

Push the burst workflow to the default branch, then trigger
`workflow_dispatch` once and watch `gh run list`/Render metrics to confirm
backend stays warm for the 9-minute burst. Then migrate off the constrained
GitHub cron by an external pinger if realistic runs keep skipping.

## 2026-10-31 - P2.0d: user-facing reconnect ("push to connect") in the app

**Status:** complete

### Completed

- Home header status pill is now tappable: `Offline`/`Reconnecting` taps
  straight into `HomeViewModel.reconnect()`, which sets the
  `isReconnecting` UI state, mints (or refreshes) the phone token via
  `ApiClient.ensurePhoneToken()` on the IO dispatcher, then runs
  `refreshHealth()`.
- `OfflineReconnectBanner` composable shows when the header pipeline is
  loaded, profiles exist, and `isConnected` is false: message "Backend
  unreachable" plus the explanation "Render free tier sleeps between pings -
  rings still wake it" with a Connect button that shares `reconnect()` and
  shows progress while in flight.
- Both paths disabled while `isReconnecting` so taps cannot double-fire.

### Why this was done

Free-tier Render spins down and the app's idle home screen only showed the
stale "Ready" without explanation, with no user action to recover. Users
on a sleeping backend need both a visible reason and a manual recovery
action ("push to connect"), especially after a ring was dropped by a
waking instance.

### Changed files

- `mobile/android/app/src/main/java/com/agentcall/app/home/HomeScreen.kt`
- `mobile/android/app/src/main/java/com/agentcall/app/home/HomeViewModel.kt`

### Validation

- `gradlew.bat testDebugUnitTest assembleDebug` BUILD SUCCESSFUL (2m 11s);
  diff installed on the physical device (new APK, `adb install -r`).
- Live UI test on device with network disabled via `svc data disable`:
  header pill showed `Offline`, banner appeared with the exact copy
  expected, `Connect` button tapped after re-enable: pill flipped to
  `Ready` and the banner disappeared (uiautomator dump evidence).
- Deterministic offline verification caveat: ColorOS auto-restores mobile
  data within ~45 s after `svc data disable`, so the offline window is
  short; airplane mode cannot be toggled by adb on this ColorOS build
  (SecurityException on the AIRPLANE_MODE broadcast). Consider testing in
  a ROM that honors adb-driven airplane mode, or adding a debug hook to
  fake network unavailability.

### Next task

Physical-device validation of the remaining P0.1b items (human voice
barge-in metrics inside the call, real STT on the 300 ms cut target); then
decide whether to keep the 9-minute burst workflow or shift to an external
pinger; still unresolved is the ColorOS Hans freeze gap in P2.0a items (2).

## 2026-10-31 - P2.0e: keep-warm is GitHub Actions-only (phone heartbeat removed)

**Status:** complete

### Completed

- Removed the on-device `/health` heartbeart layer: deleted
  `WarmupPingWorker.kt` (and the empty `warmup/` package) and dropped its
  registration from `AgentCallApp.onCreate`, per user decision that periodic
  phone-side pings are an unacceptable battery/data cost.
- Because `ExistingPeriodicWorkPolicy.KEEP` enqueues persist across app
  reinstalls (they survive under `no_backup/`), the new build calls
  `WorkManager.getInstance(...).cancelUniqueWork("render-warmup-ping")` on
  startup. This cancels the spec enqueued by the previous APK on updated
  devices without touching any app data (profiles, call history, FCM token).
- `DEPLOYMENT_GUIDE.md` keep-warm section rewritten: GitHub Actions-only
  burst + agent-MCP/ring-driven wake, with the external-pinger option kept
  documented for when scheduler lag unacceptable.

### Why this was done

Warm-up via GitHub alone is viable for this deployment: the repo is public
(Actions minutes are free), and the burst pattern holds Render ~9 min per
fired run, consuming roughly 430 of the 750 free Render instance-hours.
The phone heartbeat risked battery concern for marginal benefit.

### Changed files

- `mobile/android/app/src/main/java/com/agentcall/app/AgentCallApp.kt`
- `DEPLOYMENT_GUIDE.md`
- `mobile/android/app/src/main/java/com/agentcall/app/warmup/WarmupPingWorker.kt` (deleted)

### Validation

- `gradlew.bat testDebugUnitTest assembleDebug` successful; APK reinstalled
  (`adb install -r`) and app launched (`pidof` healthy, home UI normal).
- Device verification: `dumpsys jobscheduler` no longer lists any
  `com.agentcall.app` `androidx.work.impl.background.systemjob` job (was
  present with the previous APK); WorkManager on startup now holds the
  cancel call, so ping work cannot re-enqueue itself.
- Historical WorkSpec strings remain inside the app-private `workdb` after
  cancellation (expected); state stays CANCELLED, no job runs.

### Next task

Monitor a full day of `keep-render-warm` runs with the updated schedule
(`gh run list --workflow keep-render-warm`) to characterize scheduler-lag
behavior; if gap-to-gap uptime is poor, consider chaining app-observed
caveat: none (app no longer participates). Else continue P0.1b barge-in
physical validation.
