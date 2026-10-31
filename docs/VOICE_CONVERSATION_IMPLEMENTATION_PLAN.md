# Voice Conversation Implementation Plan

**Status:** active  
**Owner:** AgentCall maintainers  
**Last updated:** 2026-10-31  
**Companion log:** [`VOICE_CONVERSATION_PROGRESS.md`](VOICE_CONVERSATION_PROGRESS.md)

## 1. Goal and non-goals

Build a reliable, interruptible voice conversation between a human using the
AgentCall mobile app and an AI agent using MCP, without coupling the product to
one model vendor or agent harness.

The product target is:

- a user can interrupt an AI while it is speaking;
- the audio stops promptly, the user's response is transcribed, and the owning
  agent receives one reliable turn result;
- local audio remains the default for privacy, latency, and offline resilience;
- ChatGPT, Claude, OpenCode, Codex, and custom agents use the same supported
  remote MCP interface;
- WebRTC is introduced only when remote media, browser participation, or a
  realtime voice model makes it necessary.

This plan does **not** make WebRTC a prerequisite, replace the current backend,
or assume that every harness can expose LLM tokens while generating a response.

## 2. Verified starting point

| Area | Current state | Implication |
| --- | --- | --- |
| Agent integration | Remote, stateful Streamable HTTP MCP endpoint and text-oriented tools (`create_call`, `send_message`, `send_message_and_wait`, etc.) | Good cross-harness base; add capabilities compatibly. |
| Backend/mobile transport | Backend-to-phone signaling is a WebSocket, with FCM for wake/ring resilience | This carries JSON events, not RTP/WebRTC media. |
| AI speech | Android uses bundled Piper through sherpa-onnx and `AudioTrack` | Local TTS is the correct default. |
| User speech | Android uses platform `SpeechRecognizer` | This is not guaranteed offline and is a separate microphone owner from the VAD tap. |
| Barge-in | `BargeInController` uses RMS frames, `VOICE_COMMUNICATION`, Android AEC and noise suppression where supported | Better than raw mic capture, but fixed thresholds are weak in realistic noise. |
| TTS cancellation | `AudioTrack` playback stops quickly; Piper JNI synthesis itself is not hard-cancellable | Short sequential chunks are essential. |
| Deployment | Render free web service hosts backend; repo has coturn Docker configuration intended for VPS-style deployment | Render is appropriate for API/signaling, not a public TURN relay. |

The source baseline is recorded in the progress log. Existing documents must not
be treated as proof that a device scenario is validated; device validation must
produce reproducible logs and results.

## 3. Target architecture

### 3.1 Default local-media path

```text
Agent harness
  │ MCP tool calls / optional semantic text chunks
  ▼
AgentCall backend ── durable call + transcript state ── Android/iOS
  │                                                     │
  └────────── WebSocket/FCM JSON events ────────────────┤
                                                        │
                              local TTS ◄─ speech queue │
                                                        │
 user voice ─► one owned audio capture ─► AEC/NS ─► VAD ─► STT
                                                        │
                                 final turn ────────────┘
```

The phone keeps audio local. Only text, state transitions, and diagnostics
cross the network. This remains the normal path even after WebRTC exists.

### 3.2 Optional future remote-media path

```text
Android / iOS / browser ── WebRTC (Opus) ── media gateway/SFU
                                                │
                                   transcripts, commands, audit events
                                                │
                                          AgentCall backend
                                                │
                                              MCP/REST
                                                │
                                           AI harnesses
```

The media gateway is a separate service (for example LiveKit, Janus, or
mediasoup), with coturn on a VPS. The Node/Fastify application remains the
control plane and must not become a general media-packet processor.

## 4. Product and engineering invariants

1. The server is authoritative for call ownership, lifecycle, idempotency, and
   durable transcript events.
2. The device is authoritative for microphone permission, local audio route,
   and immediate playback interruption.
3. Every state-changing client message has an idempotency identifier.
4. A named AI is not an identity. All authorization uses immutable key/agent
   IDs, while display names remain mutable presentation data.
5. Audio capture has exactly one owner at a time. Any handoff is explicit,
   measured, and tested on real devices.
6. A barge-in can never cause the user to lose their speech silently. If
   automatic STT cannot start, the UI and logs show a recoverable failure.
7. Remote integrations expose least-privilege tools and require user consent
   for initiating calls.

## 5. Delivery phases

### Phase 0 — Establish a reliable local voice baseline

**Why first:** It improves user experience without a media server, costs less,
and isolates mobile audio problems before adding network audio.

#### 0.1 Instrument and validate the existing path

Add structured, privacy-safe timing fields for:

- TTS queue dequeue to first audio write (TTFA);
- speech onset to audio cut;
- VAD stop/release to STT ready;
- STT ready to final transcript;
- final transcript to durable backend acknowledgement;
- route (earpiece, speaker, Bluetooth), AEC/NS availability, and failure code.

Do not log raw audio or full transcript content in production metrics. Use
call-scoped correlation IDs, duration values, and redacted error classes.

**Acceptance criteria**

- A real-device test matrix covers earpiece, speaker, Bluetooth, quiet room,
  television/background speech, and noisy environment.
- The project has a repeatable test script and a human-test checklist.
- P50/P95 values are recorded, not estimated from code.

#### 0.2 Upgrade VAD from fixed RMS to Silero VAD

Add a VAD abstraction with two implementations:

- `RmsVadEngine`: retained only as a guarded fallback;
- `SileroVadEngine`: primary engine using the existing sherpa-onnx runtime and
  a pinned, checksummed `silero_vad.onnx` asset.

Silero must run after the platform capture pre-processors. Its threshold,
minimum speech duration, and silence duration are remote-configurable within
safe bounds and reported with each test result.

**Acceptance criteria**

- Model provenance, licence, version, SHA-256, and asset size are documented.
- VAD is unit-tested with stored, consented PCM fixtures and integration-tested
  on physical devices.
- False interruption rate and missed-interruption rate are measured per route.
- Failures fall back to RMS only when explicitly allowed by configuration.

#### 0.3 Establish one capture/recognition owner

The current VAD `AudioRecord` must release before `SpeechRecognizer` starts;
this creates an OEM-dependent handoff. Investigate two implementations:

1. platform recognizer with an explicit handoff delay/retry policy; and
2. on-device streaming STT reading the same owned audio capture as VAD.

Choose option 2 only after validating language quality, APK/memory impact,
battery use, and device coverage. Do not claim fully offline STT while using
the platform recognizer.

**Acceptance criteria**

- No `ERROR_RECOGNIZER_BUSY` in the test matrix without a visible recovery.
- Partial results reach UI state; only endpointed final results become a user
  transcript turn.
- Cancelled speech, empty speech, permissions denied, and recognizer failure
  have distinct user-visible outcomes.

#### 0.4 Bound TTS cancellation latency

- Split content into small speakable chunks (target 8–15 words).
- Synthesize/play sequentially, at most one chunk ahead.
- Flush/fade `AudioTrack` immediately on interruption.
- Limit excessive punctuation and prohibit long prose from the agent prompt.

**Acceptance criteria**

- P95 onset-to-cut is at most 300 ms while PCM is playing.
- No chunk longer than the selected synthesis-cancellation budget is queued.
- The known non-cancellable JNI synthesis delay is measured and explicitly
  reported rather than hidden.

### Phase 1 — Make turns and text delivery conversational

#### 1.1 Explicit turn state machine

Introduce durable events such as:

`assistant.speaking`, `user.interrupted`, `user.listening`, `user.partial`,
`user.turn_completed`, `assistant.thinking`, `call.completed`.

The mobile UI may render partial text locally, but only a finalized, endpointed
turn changes the backend transcript. Events must be monotonic and idempotent.

#### 1.2 Optional semantic text streaming

Keep current tools for every MCP client. Add a negotiated optional capability
for harness adapters that can stream text:

- `start_assistant_turn(call_id, turn_id)`
- `append_assistant_text(call_id, turn_id, text, sequence)`
- `finish_assistant_turn(call_id, turn_id)`
- `cancel_assistant_turn(call_id, turn_id, reason)`

Chunks are sentence/phrase-level and coalesced for roughly 120–250 ms; they are
not raw model tokens. The backend orders, validates, persists, and forwards
them. A user interruption invalidates later sequence numbers for that turn.

**Acceptance criteria**

- Older agents using `send_message` remain fully functional.
- Duplicate/out-of-order chunks do not produce duplicate audio.
- A cancellation cuts speech and prevents remaining chunks from being spoken.

### Phase 2 — Professionalize identity, security, and integration

1. Migrate per-call authorization from agent display name to immutable `agentId`.
2. Add OAuth 2.1 + PKCE for hosted ChatGPT/Claude-style connectors and retain
   scoped, revocable keys for local developer harnesses.
3. Add scopes such as `calls:create`, `calls:send`, `calls:read`, and
   `calls:complete`; bind each key to a user and consent policy.
4. Show a clear pre-call consent policy: allowed hours, caller identity,
   priority, maximum ring frequency, and revoke control.
5. Publish one versioned MCP contract, an OpenAPI REST fallback, examples for
   OpenCode/Claude/Codex, and a compatibility test suite.
6. Make MCP upgrades deliberate: test supported protocol versions and client
   behavior before changing the stateful endpoint model.

### Phase 3 — Production operations

- Move production API/signaling from a sleeping free instance to an always-on
  paid Render web service or equivalent.
- Put PostgreSQL, session/presence state, and rate-limit counters in shared
  durable storage before horizontal scaling.
- Implement graceful shutdown, reconnect/resume, health/readiness endpoints,
  OpenTelemetry traces, alerting, backups, and a privacy retention policy.
- Keep coturn on a VPS; validate relay allocation from restrictive mobile
  networks before launch.

### Phase 4 — WebRTC only when justified

Enter this phase only when at least one requirement exists: cloud STT/TTS,
realtime-model audio, browser participation, recording/transfer, or remote
human handoff.

Build in this order:

1. coturn VPS and credential rotation;
2. media gateway proof of concept with Opus, TURN-only test, reconnection, and
   no transcript/data loss;
3. mobile WebRTC audio integration with route, AEC, Bluetooth, and background
   call tests;
4. production media observability: RTT, jitter, packet loss, relay ratio,
   ICE failures, and per-call cost;
5. staged rollout behind a feature flag, keeping local-media mode as fallback.

## 6. Decisions deliberately deferred

- Select a specific Silero model only after license/provenance review.
- Choose streaming on-device STT only after a device and language benchmark.
- Choose LiveKit, Janus, or mediasoup only when Phase 4 is authorized.
- Support iOS after defining parity requirements; Android behavior is not
  automatically portable.
- Expose a ChatGPT Apps SDK UI only when an in-chat visual workflow adds value;
  MCP tools remain the universal path.

## 7. Definition of done for a professional release

The release is ready only when all of these are true:

- documented call and turn state machines have automated transition tests;
- device matrix results meet published latency/reliability targets;
- interruption and recovery failures are observable and actionable;
- agent identities are collision-resistant and scoped;
- all remote write actions have consent/audit controls;
- deployment recovery does not lose an active call or duplicate a transcript;
- no unsupported claim is made about offline STT, WebRTC, or device audio
  capabilities.
