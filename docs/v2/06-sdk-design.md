# 06 — SDK Design

> **Deliverable:** 9 (SDK design). Primary target: TypeScript. Python SDK (async/evented)
> follows the identical surface; both are *client* libraries, not servers.

## 1. Design goals

1. **Feels like Twilio Realtime + EventEmitter.** `create()`, `say()`, `on("*")`,
   `waitForReply()`.
2. **Event-driven first.** Every server event maps to a typed `call.on(event, handler)`.
3. **No polling. Ever.** `waitForReply()` is a local await that resolves on `turn.ended`
   (user) — transparently implemented over the event subscription.
4. **Backward compatible.** The same SDK can run in **managed mode** (MCP transport — works
   with today's deploy) or **native mode** (REST + WS/SSE v2 endpoints) behind one API.
5. **Voice-agnostic.** Works headless (text events) or with real audio (WebRTC/WS media) by
   passing a `media` adapter.

## 2. Two modes

| | Managed mode (v1-compatible) | Native mode (v2) |
|---|---|---|
| Transport | MCP Streamable HTTP (`/mcp`) | REST `/api/v2` + WS `/v2/events` (+ `/v2/media`) |
| Events | `subscribe_to_events` (SSE URL) | native envelope stream, resumable |
| `waitForReply` | turn lease + event sub | turn lease + event sub (identical semantics) |
| Feature ceiling | v1 tool set | full v2 (partials, barge-in, tools, transfer) |
| When | against a v1-only server | against v2 server |

SDK auto-selects: `new AgentCall({ baseUrl, token })` probes `/v2/health`; native if present,
managed otherwise. Integration code written once runs against either.

## 3. Core surface

```typescript
import { AgentCall } from '@agentcall/sdk';

// A connection to the platform (one token = one AI identity)
const agentCall = new AgentCall({
  baseUrl: 'https://call.example.com',
  token: process.env.AGENTCALL_AI_KEY,       // AI key minted by the phone app (v1 already does this)
  media: { stt: 'on-device', tts: 'on-device' },   // provider defaults per call
  autoReconnect: true,
});
await agentCall.ready();

// ── Create a persistent call ─────────────────────────────────────────────
const call = await agentCall.create({
  userId: 'user_123',
  reason: 'approval',
  summary: 'Approve invoice #4412 for payment?',
  context: { task_id: 't-9', options: ['Approve', 'Reject', 'Ask a question'] },
});

// ── Speak (streaming TTS; returns immediately; non-blocking) ─────────────
await call.say("Hello! I need your approval on invoice #4412.");

// ── React to the human, event-driven ─────────────────────────────────────
call.on('speechFinal', async (event) => {           // final, committed text
  await call.say(generateResponse(event.text));
});
call.on('speechPartial', (event) => {               // live while talking (optional)
  ui.updatePreview(event.text);
});
call.on('interrupted', () => {                       // user barged in; TTS cut
  cancelPendingGeneration();
});
call.on('silenceDetected', (e) => {                  // AI decides — no server cap
  if (e.count >= 2) await call.say("Are you still there?");
});
call.on('dtmf', (e) => { /* IVR-style flow */ });
call.on('ended', (e) => console.log('Call finished:', e.outcome));

// ── Convenience that is still event-driven under the hood ────────────────
const reply = await call.waitForReply();              // resolves on user turn.ended
//   { utterance_id, text, confidence, start_ms }     // or rejects on call ended

// ── Tools mid-call (never visible to the human) ──────────────────────────
const availability = await call.invokeTool('calendar.check', {
  date: '2026-08-06',
  range_hours: 3,
});

// ── End ──────────────────────────────────────────────────────────────────
await call.hangup({ outcome: { decision: reply.picked('Approve') }, note: 'approved over phone' });

// ── Transcript (live, partials optional) ─────────────────────────────────
const segments = await call.getTranscript({ after: 40, partials: true });
```

## 4. Event mapping (kebab → camel)

| Server event | SDK event | Argument type |
|--------------|-----------|---------------|
| `call.created/ringing/connected/paused/resumed/transfer.requested/transfer.completed/transfer.failed/ending/completed/failed/archived` | `created` … `archived` | `CallLifecycleEvent` |
| `speech.started` `speech.partial` `speech.final` `speech.failed` | `speechStarted` `speechPartial` `speechFinal` `speechFailed` | `SpeechEvent` |
| `transcript.updated` | `transcriptUpdated` | `TranscriptEvent` |
| `message.queued/started/completed/failed` | `messageQueued` `messageStarted` `messageCompleted` `messageFailed` | `MessageEvent` |
| `turn.lease` `turn.cancelled` `turn.ended` | `aiWaiting` `turnCancelled` `turnEnded` | `TurnEvent` |
| `user.interrupted` | `interrupted` | `InterruptEvent` |
| `silence.detected` `call.noactivity` | `silenceDetected` `noActivity` | `SilenceEvent` |
| `dtmf.received` | `dtmf` | `DtmfEvent` |
| `function_call.*` | `functionCallRequested` `functionCallCompleted` `functionCallFailed` | `FunctionCallEvent` |
| `media.*` | `mediaConnected` `mediaReconnecting` `mediaReconnected` (`mediaDisconnected` = `call.on('disconnected')`) | `MediaEvent` |
| `stream.resync` / `stream.end` | `resync` `streamEnded` | — |
| `error` | `error` | `{ code, message }` |

Catch-all: `call.onAny((event) => …)`.

## 5. `waitForReply()` — the honest implementation

```typescript
async waitForReply(timeoutMs?: number): Promise<UserReply> {
  // 1. Ensure an event subscription is open (WS or SSE, resumable).
  // 2. Take note of current transcript_seq.
  // 3. Register a turn lease with the server (marks ai_wait = true; no expiry).
  // 4. Await a `turn.ended{type:'user'}` OR `speech.final` whose sequence > noted seq,
  //    or a terminal event (`call.completed`/`call.failed`).
  // 5. Resolve with the finalized text; reject with `CallEndedError` on terminal state.
  // 6. Release the lease in `finally`.
  // pause/resume: SDK backoffs (2s→4s→8s→30s max) on network drop, resumes via Last-Event-ID.
}
```

Notes:
- There is **no server-side cap**. `timeoutMs` is an *optional local convenience*; omit it and
  the SDK waits indefinitely while the call lives (exactly the v2 promise).
- On terminal events while waiting, the SDK rejects with the call outcome (maps cleanly to the
  v1 tool's `outcome: "call_ended"` contract).

## 6. `say()` — streaming TTS path

`say(content)` = `POST /calls/:id/messages` (queued) then tracks `message.completed`. A
lightweight variant `sayAndStream(content)` returns an async iterator of audio chunks for
clients that multiplex locally. **Never** blocks on the LLM-gateway — this is the SDK's job, not
the platform's.

## 7. Programmatic control during speech

```typescript
await call.stopSpeaking();      // AI-initiated stop     → turn.cancelled(reason='ai_stop')
call.enableBargeIn();           // default on
call.disableBargeIn();          // device must finish; interrupts still tracked
await call.pause({ untilMs: 60_000 });    // call.paused
await call.resume();                       // call.resumed
await call.transfer({ target: 'agent-02' });
```

## 8. Media adapters (native mode, optional)

```typescript
interface HoopAudio {            // what the SDK hands the platform for a call
  incoming: AsyncGenerator<AudioChunk>;   // human audio → platform
  outgoing: Writable<AudioChunk>;         // platform TTS → whatever plays it
}
```
Shipping adapters: `OnDeviceAdapter` (Android/iOS native STT/TTS, $0), `WebRtcAdapter`
(browser / LiveKit / coturn), `RawWsAdapter` (WS media channel, D8 §3.3). A headless
deployment uses `NoopMediaAdapter` (an "audio display only" call — text events still work).

## 9. Error model & retries

- `AgentCallError` base; subclasses: `AuthError(401)`, `OwnershipError(403)`,
  `NotFoundError(404)`, `InvalidTransitionError(409)`, `RateLimitedError(429)` with
  `retryAfter`, `NetworkError` (auto-retried with backoff), `CallEndedError`.
- Writes carry `Idempotency-Key` automatically (retry-safe). Events dedupe by `id` internally.
- `agentCall.health()` / `agentCall.metrics()` for ops integrations.

## 10. Python SDK (planned surface, same semantics)

```python
from agentcall import AgentCall

agent_call = AgentCall(base_url=..., token=...)
call = await agent_call.create(user_id="user_123", summary="…")

@call.on("speech.final")
async def on_final(event):
    await call.say(generate_response(event.text))

@call.on("interrupted")
async def on_interrupted(_event):
    cancel_pending_generation()

reply = await call.wait_for_reply()
await call.hangup(outcome={"decision": "approved"})
```

## 11. Versioning & release

- `@agentcall/sdk` follows semver; v1 series (`0.x`) supports managed mode only; 1.0 adds
  native mode. The surface above is what 1.0 promises.
- Default install pulls zero native deps (`ws` only, bundled). Media adapters are
  optional sub-packages (`@agentcall/sdk/webrtc`, `@agentcall/sdk/on-device`).
- Docs site mirrors this file per language. All TypeScript is strict, zod-validated at the
  wire boundary, and shipped with `.d.ts`.