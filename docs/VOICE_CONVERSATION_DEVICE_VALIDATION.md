# Voice Conversation Device Validation

**Status:** ready for first physical-device run  
**Related plan:** [`VOICE_CONVERSATION_IMPLEMENTATION_PLAN.md`](VOICE_CONVERSATION_IMPLEMENTATION_PLAN.md)  
**Log results in:** [`VOICE_CONVERSATION_PROGRESS.md`](VOICE_CONVERSATION_PROGRESS.md)

## Purpose

This checklist produces evidence for barge-in and speech-turn behavior before
changing the VAD implementation. It is intentionally a human-run test: Android
cannot inject real microphone input reliably with an `adb` text command.

## Prerequisites

- A debug build installed on a physical Android device.
- `RECORD_AUDIO` granted before the test.
- Device connected to `adb` with sufficient battery and no active phone call.
- A real AgentCall call in the `active` state; do not use a synthetic call that
  fails the backend/Room foreign-key validation.
- Log capture started before answering the call:

```sh
adb logcat -c
adb logcat -v threadtime -s AgentCall > agentcall-voice-validation.log
```

The tester must record the device model, Android version, app build SHA, audio
route, approximate room condition, and whether Bluetooth is connected.

## Test cases

Run every case with a short AI prompt first, then repeat the marked cases with
a 20–30 word prompt. The user should speak a clear interruption approximately
one second after playback begins.

| ID | Route/environment | Action | Expected result |
| --- | --- | --- | --- |
| V01 | Earpiece, quiet | Interrupt a speaking AI | Playback stops; recognizer starts; final transcript is sent exactly once. |
| V02 | Speaker, quiet | Do not speak during AI output | No false barge-in. |
| V03 | Speaker, quiet | Interrupt with normal voice | Same as V01, no missed interruption. |
| V04 | Speaker, TV/background speech | Do not address the app | No false barge-in, or record the false trigger. |
| V05 | Speaker, TV/background speech | Address the app | User interruption is detected reliably. |
| V06 | Wired headset | Interrupt a speaking AI | Same behavior as V01; route change is logged. |
| V07 | Bluetooth headset | Interrupt a speaking AI | Same behavior as V01; no permanent microphone loss. |
| V08 | Permission denied | Receive a speaking AI message | Clear UI fallback; no crash; VAD-disabled reason is logged. |
| V09 | Network loss after interruption | Speak a response | User text is durably queued and sent once after reconnect. |
| V10 | Long AI message | Interrupt while the next Piper chunk is synthesizing | Measure delay; no later chunk is played after interruption. |

## Required log markers and measurements

For each run, preserve these markers when present:

```text
[VAD] started
[VAD] AEC available=… NS available=…
[VAD] speech detected …
[BARGE] speech onset during TTS …
[BARGE] auto-starting SpeechRecognizer …
[PIPER] play cut by barge-in …
[STT] …
```

Record these values in a results table:

| Field | How to calculate | Target / interpretation |
| --- | --- | --- |
| Barge detect latency | first audible user speech to `[VAD] speech detected` | Measure; target P95 ≤ 250 ms after VAD tuning. |
| Playback-cut latency | `[VAD] speech detected` to `[PIPER] play cut` | Target P95 ≤ 300 ms while PCM is playing. |
| Mic handoff latency | barge marker to recognizer ready/beginning-of-speech | Track separately by OEM; must have a recovery path. |
| TTFA | AI message dequeue to first `AudioTrack.write` | Baseline for regression detection. |
| False barge-in rate | false triggers / no-user-speech runs | Compare RMS and future Silero runs. |
| Missed-interruption rate | missed attempts / intended user interruptions | Compare RMS and future Silero runs. |
| Transcript delivery | final user turn accepted once by backend | Must be 100% for successful network/retry scenarios. |

Do not put raw audio, API keys, phone numbers, or full user transcript text in
the result document. Use run IDs and short redacted notes.

## Result template

```markdown
### Run YYYY-MM-DD / device / build

| Case | Pass | Route | AEC/NS | Detect ms | Cut ms | Handoff ms | Notes |
| --- | --- | --- | --- | ---: | ---: | ---: | --- |
| V01 |  |  |  |  |  |  |  |

Log attachment: `artifacts/voice-validation/<run-id>.log` (not committed if it
contains personal data).
Decision: keep RMS / tune RMS / proceed to Silero comparison.
```

## Exit criteria for this validation stage

Do not switch production VAD based on a single successful demo. At least two
physical device models and the V01–V05 cases must have results. Any crash,
permanent mic loss, duplicate transcript, or unobserved failure blocks the
Silero rollout until triaged.
