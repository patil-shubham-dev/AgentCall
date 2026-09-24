# Live Tests Remaining — Hardened via Code Audit (2026-09-24)

Three behaviors could not be validated without a physical device. This session
(audit + hardening, **no device available**) traced each through three lenses —
failure/edge-cases, concurrency/lifecycle, known platform quirks — and fixed
what was clearly broken. **Nothing here is marked PASS/WORKING/FIXED: every
item below is "hardened via code audit, still requires live validation."**

Device context (from prior sessions): RMX3867 (Realme, ColorOS, API 35),
device id `NZUSAIFEPNX8W4PV`. adb is not on PATH:

```bash
export PATH="$PATH:/c/Users/91808/AppData/Local/Android/Sdk/platform-tools"
```

ColorOS-specific realities to re-check before relying on any procedure: the
Doze whitelist entry is wiped on reinstall
(`dumpsys deviceidle whitelist +com.agentcall.app` to restore for the
whitelist-*control* tests only — item 3 must run WITHOUT it), `pm grant` is
blocked by the OEM (runtime permissions must be granted through the real UI
dialogs), and the standby bucket is OEM-managed (5 = EXEMPTED, not
shell-settable).

Commits from this session:

- `36165c8` fix(barge-in): un-latch TTS stop, release mic before STT handoff, bounded VAD watch
- `795e073` fix(ring): surface mid-call disconnect in UI, pending-only ring replay gate
- `1c5106b` fix(ring): server-side re-push for Doze-deferred FCM rings (unplugged hardening)

---

## Item 1 — Real-voice barge-in interruption + STT handoff

### Session hardening (`36165c8`)

Three code defects found by the audit, all in the cut-and-handoff seam:

1. **TTS stop latch (worst):** `PiperTtsEngine.stopRequested` latched on
   barge-in and was never reset in the paced playback path — after ONE
   barge-in, every subsequent AI message would silently no-op (`synthesize`
   returns null on a set flag) and the call goes mute. Fixed with
   `startNewUtterance()` called at the top of `speakWithPiper`.
2. **Mic contention at handoff:** `BargeInController` stopped its loop but
   did not release the `AudioRecord` (or AEC/NS effects) before invoking the
   barge-in callback — the auto-started `SpeechRecognizer` could race a
   still-claimed mic. Now stopped + released synchronously *before*
   `onBargeIn` fires.
3. **Wrong-thread recognizer + lingering VAD tap:** `handleBargeIn` created
   `SpeechRecognizer` from the VAD's IO loop (platform requires the main
   thread — now hopped); the system-TTS fallback path left the VAD mic tap
   armed on a blind 30 s timer after speech ended, letting the user's next
   words self-trigger a barge-in (now stops when the utterance completes,
   30 s cap kept as fallback).

### How to test (manual, needs the phone)

Build & install debug, grant RECORD_AUDIO via the in-app flow, connect to the
prod backend, start a call from the MCP driver, then:

1. During the AI's greeting (a 3+ sentence summary, 8–12 s of speech), speak
   a clear word mid-second-sentence ("Hey — stop").
2. Watch logcat: `adb logcat -s AgentCall` (or `adb logcat | grep -E
   '\[VAD\]|\[BARGE\]|\[PIPER\]|\[STT\]'`) and capture to a file for the
   session: `adb logcat -v time > artifacts/logcat/barge-live-1.txt`.
3. Repeat the barge-in at least 3 times **in the same call** (this is what
   the stop-latch fix targets), and also barge during the LAST sentence.

What live validation must confirm that no audit could:

- Real acoustic barge-in actually fires: `[VAD] speech detected rms=…`
  followed by `[BARGE] speech onset during TTS …` and `[PIPER] play cut by
  barge-in after offset=…` — latency (speech onset → audio cut) measured
  from log timestamps; target 150–300 ms, worst case bounded by ≤1 chunk
  (~1 s) after the 12-word sub-chunking.
- The STT handoff actually hears the interruption: after the cut,
  `[BARGE] auto-started SpeechRecognizer isRecording=true` (or the new
  main-thread handoff log) followed by an `onResults` that transcribes the
  interrupting words and the backend receiving the user text
  (`[USER-TEXT] backend confirmed`).
- **No mute after barge:** the AI's reply to the interruption is actually
  spoken (audible + `[BENCH]`/`[PIPER]` logs for the reply message). This
  is the regression the stop-latch fix claims to fix — it was audited as a
  certain bug, but the fix has never run on hardware.
- Mic handoff reliability: 3/3 barge-ins in one call all complete the
  STT round trip with no `ERROR_RECOGNIZER_BUSY` and no `[VAD]` mic errors.
- AEC behavior on the earpiece route with a real voice (false-positive
  self-barges were only measurable through a speaker loop before).

Honest confidence: the cut-and-handoff logic now looks correct and
race-free on paper — the stop-latch fix in particular repairs a
definitely-broken sequence — but none of it has met a real microphone;
acoustic thresholds (1400/4000 RMS) and handoff timing are untested
against live speech.

---

## Item 2 — Mid-call WebSocket kill by Render (untested failure mode)

### Session hardening (`795e073`, plus prior art)

Render behavior (documented, checked this session): no fixed WS timeout, but
connections close on every instance replace (deploys) and free-tier spin-down
counts WS traffic — a mid-call kill is a *when*, not an *if*. The client
already had reconnect with backoff/jitter, a short-lived-streak parking
guard, generation guards, a WS-down transcript poll, and a terminal-check
poll (added earlier). The audit found two gaps, now fixed:

1. **Stale UI after reconnect surrender:** `CallViewModel` ignored
   `DISCONNECTED` — after `MAX_RECONNECT_ATTEMPTS` the call UI kept showing
   "Connected". Now DISCONNECTED mid-call maps onto the RECONNECTING phase
   ("Reconnecting — call stays live"); teardown still belongs to terminal
   events, the WS-down terminal check, and the 30-min watchdog only.
2. **Replayed ring clobbering a live call:** both ring validators (FCM path,
   FGS fallback poll) accepted server status `active`, so a queued/replayed
   `call_incoming` delivered right after a reconnect could re-ring an
   already-answered call, overwrite `CallStateHolder`, and arm a 60 s
   timeout against the live session. Both now gate on `pending` only
   (`RingTimeoutPolicy.shouldRingForServerStatus`, 3 new unit tests).

Also confirmed this session: WS events lost during the gap are recovered
(AiMessage re-fetch via the transcript poll; terminal states via the 20 s
terminal check; `reSyncCall` on reconnect), and orphaned-state risks are
bounded (persisted retry queues for user text/answer/cancel/complete;
`releaseCallResources` one-shot funnel).

### How to test (manual, needs the phone + a second device or MCP driver)

1. Start a call from the MCP driver (or a second phone), let the AI speak.
2. Kill the connection mid-call. Reproduce Render's two kill modes:
   - *Network kill:* enable airplane mode for ~20 s, then disable it.
   - *Server kill:* redeploy the backend (or wait for a spin-down/wake
     cycle) while the call is active.
3. Logcat throughout: `adb logcat | grep -E '\[WS\]|\[NET\]|Reconnect'`.
4. While the socket is down, send a user text (typed is fine) and have the
   agent reply; confirm the reply still arrives and is spoken (transcript
   poll path: `[WS]` absent, but new AI bubble + TTS playback).
5. End state: the call must end ONLY via a real terminal event, the
   terminal-check poll, or the user's End button — never because the socket
   died.

What live validation must confirm that no audit could:

- "Reconnecting — call stays live" actually appears at the moment the WS
  dies (both kill modes), and the in-call timer pauses.
- The reconnect actually completes after airplane mode off
  (`[WS] opened` → `reSyncCall`), and messages sent during the gap arrive
  exactly once (idempotency + dedupe holding up).
- No zombie: after the reconnect budget is exhausted (or server killed),
  the session ends through the terminal check / watchdog rather than
  hanging, and no notification or wake lock survives (`adb shell dumpsys
  notification`, `dumpsys power | grep VoiceBridge`).
- A replayed `call_incoming` for the active call (redeploy during ring
  dispatch) does NOT re-ring the answered call — the pending-only gate's
  live confirmation.

Honest confidence: the reconnect machinery was already solid and the two
seams found (UI truthfulness, ring-replay gate) are closed with unit
coverage — but the full sequence (kill → backoff → re-register → re-sync →
deliver-lost-messages) has never been observed end-to-end against a real
Render deploy boundary, and ColorOS may additionally kill the whole process
in ways no in-process audit can see (then it's the FCM/missed-call path, not
reconnect, that owns recovery).

---

## Item 3 — Overnight ring on an unplugged, non-Doze-whitelisted device

### Session hardening (`1c5106b`)

The audit's core finding: on an **unplugged, non-whitelisted** device, all
prior validation conditions (USB-powered, whitelist-exempt) were
meaningfully easier, and the failure surface is layered:

- Backend: FCM HTTP 200 was treated as terminal; Google only confirms
  transport acceptance, and Firebase docs state Doze **may delay even
  high-priority messages** (and grants only "very limited" network for
  processing). Nothing re-attempted a deferred ring.
- Phone: the FGS fallback poll is foreground/ring-gated (no FGS while idle
  in the FCM-only model), the 60 s timeout alarm silently degrades to
  inexact when `SCHEDULE_EXACT_ALARM` is revoked (the default grant state
  at targetSdk 35 must be checked live), and the 3-min pending-TTL sweep
  then records a missed call the user never saw.

Fixed server-side (`1c5106b`): when the WS leg is down and the FCM send is
accepted, the backend schedules **one deferred re-push** (45 s later,
skipped if the ring window has <60 s left; re-enters the ring gate so
answered/cancelled calls are silent no-ops; absorbed by the phone's
single-ring dedupe if the first push actually arrived; window-bounded at
~3 nudges). Backend suite extended (`fcm-repush.test.ts`, 5 tests; 252
total green). Client-side, the exact-alarm inexact fallback and
RingTimeoutReceiver's server-pre-check were already correct and were left
alone; the ColorOS battery-optimization guided flow
(`BatteryOptimizationScreen`) is the human-facing mitigation and stays.

### How to test (manual, needs the phone + overnight + ideally a second device)

Prepare (evening):

1. Install the current debug build; grant RECORD_AUDIO + notifications
   through the real UI dialogs; complete the ColorOS battery-optimization
   guided flow (or deliberately skip it to test the un-exempted path —
   decide which before starting and note it).
2. **Unplug the phone. Leave it off charger, screen off, stationary.** Do
   NOT whitelist it (`dumpsys deviceidle whitelist` must NOT contain
   `com.agentcall.app` for this test). For a true deep-Doze condition, let
   it sit 30–60+ minutes before triggering.
3. Optional strict-Doze forcing (rootless): `adb shell dumpsys battery
   unplug` then `adb shell dumpsys deviceidle force-idle` (revert with
   `dumpsys battery reset` + `dumpsys deviceidle unforce`). If the OEM
   blocks these, natural idle is the condition — note which you ran.
4. Overnight (or after the idle wait), trigger the ring from the MCP
   driver / a second device. Repeat several times across the night with
   varied gaps (5 min, 30 min, 3 h).
5. Morning forensics:
   - Device: `adb logcat -b all -d -v time > artifacts/logcat/doze-ring-1.txt`
     then grep for `[FCM] ring push received`, `[RING] validation fetch`,
     `[DIAG] ring_posted_direct`, `[RING] timeout alarm scheduled exact=`.
   - Server: Render logs for `[diag:pushCallIncoming]`, `[ring] scheduled
     FCM re-push`, `[ring] firing deferred FCM re-push`, `[ring-gate]`
     dispatch results.
   - Battery: `adb shell dumpsys batterystats com.agentcall.app | head -50`.

What live validation must confirm that no audit could:

- Whether the push arrives at all while unplugged + deep Doze, and its
  latency distribution (`[diag:fcm_send_start]` → `[FCM] ring push
  received` across both transports). The re-push policy's real value —
  "first send deferred, second lands" — is only observable here.
- Whether `canScheduleExactAlarms()` is true on this ColorOS build
  (manifest declares SCHEDULE_EXACT_ALARM; the revoke-by-default question
  is decided by the OS, not the code): grep `exact=true|false` in the ring
  timeout log line.
- Whether ColorOS defers FCM even after the in-app guided exemption
  (OEM layers sit above stock Doze and were the historical killer).
- Battery cost of the hardened state (the audit could not measure the
  standby drain of the parked/FCM-only idle model on this ROM).

Honest confidence: the server now has a real second chance for
Doze-deferred rings and the phone's dedupe makes that safe on paper —
but whether the first (or any) FCM push reaches this specific ROM at 3 a.m.
unplugged and un-whitelisted is an empirical question about ColorOS +
Google's network that no code audit can settle; this test is the one that
actually decides the overnight-call product claim.

---

## Status discipline

All three items: **hardened via code audit, still requires live
validation.** No PASS/WORKING/FIXED claims anywhere in this doc or the
commit messages; the commits say "hardened via code audit, still requires
live validation" verbatim. Two fixes are behavior-changing on paths that
never ran on hardware (barge-in stop-latch reset; FCM re-push), so even
"the fix is correct" is provisionally believed, not proven.
