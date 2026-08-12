# 01 — AgentCall v2 Architecture

> **Deliverables:** 1 (complete architecture), 2 (component diagram), 4 (state machine diagram)
> **Prerequisite reading:** [README.md](./README.md), [03-event-model.md](./03-event-model.md)

---

## 1. Architectural overview

### 1.1 One-sentence architecture

AgentCall v2 is a **persistent-session, event-streaming communication platform**: a call is a
long-lived aggregate whose state transitions (ringing, connected, listening, speaking, paused,
completed, …) are emitted as versioned events into a durable event log; AI and human sides are
connected through pluggable media providers (transport, STT, TTS) and subscribe to the same
event stream, so *no party ever blocks on a timeout*.

### 1.2 Layering

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CLIENT & INTEGRATION LAYER                           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌───────────────────┐ │
│  │ ChatGPT  │ │  Claude  │ │  Gemini  │ │  OpenCode │ │ Human devices     │ │
│  │ (GPT/    │ │ (MCP/    │ │ (REST/   │ │ (MCP      │ │ Android · iOS ·   │ │
│  │  Actions)│ │  API)    │ │  funcs)  │ │  native)  │ │ Browser · SIP ·   │ │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ │ Desktop · PSTN*    │ │
│       │            │            │            │       └─────────┬─────────┘ │
│  ┌────▼────────────▼────────────▼────────────▼───────────────┐ ▼ (D4)      │
│  │  AGENTCALL SDK  (TypeScript · Python later)               │  │          │
│  │  managed mode (MCP transport) | native mode (WS/SSE)      │  │          │
│  └───────────────┬───────────────────────────────────────────┘  │          │
└──────────────────┼──────────────────────────────────────────────┼─────────┘
                   │ MCP Streamable HTTP          │ REST / SSE / WS │ WebSocket / WebRTC
┌──────────────────▼──────────────────────────────▼────────────────▼───────────┐
│                        API & AUTH GATEWAY (Fastify)                          │
│  authn/authz · ownership · rate limits · idempotency keys · audit · Zod      │
├──────────────────────────────────────────────────────────────────────────────┤
│                           APPLICATION LAYER                                  │
│  ┌───────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │ CallService       │  │ TurnCoordinator  │  │ ToolInvoker              │  │
│  │ FSM · aggregate   │  │ turn leases ·    │  │ function_call.requested  │  │
│  │ · lifecycle       │  │ barge-in ·       │  │ · completed · retries    │  │
│  └─────────┬─────────┘  │ silence policy   │  └────────────┬─────────────┘  │
│            │            └────────┬─────────┘               │                │
│  ┌─────────▼─────────────────────▼─────────────────────────▼─────────────┐  │
│  │ MediaGateway — provider registry + orchestration                      │  │
│  │  TransportProvider(WebRTC/SIP/Browser/Mobile) · STT · TTS · VAD       │  │
│  └────────────────────────────────┬──────────────────────────────────────┘  │
├───────────────────────────────────┼────────────────────────────────────────┤
│  ┌─────────────────────────────── ▼ ────────────────────────────────────┐  │
│  │ EVENT PLANE — StreamAdapter interface                                 │  │
│  │  InProcessBus ($0 default) · RedisStreams (scale) · NATS/Kafka (opt.) │  │
│  └───────────────────────────────┬──────────────────────────────────────┘  │
│  ┌────────────────────────────── ▼ ────────────────────────────────────┐  │
│  │ DURABLE STATE                                                  │  │
│  │  Postgres 16: event_log · sessions_view · transcripts · tools   │  │
│  │  · callbacks · media · recordings · audit · (Redis cache opt.) │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Data flow, in one paragraph

A human answers on a phone → `TransportProvider` feeds audio frames into `MediaGateway` →
STT provider emits `speech.partial`/`speech.final` → TurnCoordinator finalizes a user turn →
`EventPlane` records `speech.final`/`transcript.updated` in the event log and fans out to all
subscribers (AI SDK, MCP clients, dashboard) → the AI responds via SDK/API/MCP with a message →
`MessageQueued` → TTS provider streams audio through the same gateway → `MessageStarted` →
audio frames travel to the device → `MessageCompleted`. The human interrupts → STT detects
speech → `user.interrupted` → TTS hard-stops (barge-in) → the AI receives the interruption and
the new user turn continues the conversation. No party waits on a timer; the only timers are
*configurable policy timers* (silence, ringing timeout) whose firing *emits events for the AI to
handle*, not hard-coded limits.

---

## 2. Core concepts

### 2.1 The session aggregate

Every call is a `CallSession` aggregate, identity = `call_id` (UUID v7 for sortability). It owns:

| Facet | Contents |
|-------|----------|
| Identity & parties | `call_id`, `user_id`, `agent_id`, participants list |
| FSM state | current state + `fsm_version` (optimistic concurrency) |
| Conversation | transcript (finalized + partial), active utterance, turn state |
| Audio metadata | provider ids, sample rate, codec, VAD settings |
| Pending AI actions | queued messages, in-flight turn lease |
| Tool state | in-flight invocations per call |
| Context memory | `context` JSONB (task id, summary, options, custom AI-pinned data) |
| Metrics | latency, token usage, quality counters (live + persisted) |
| Policy | silence config, ring timeout, no-answer policy, retention |

**Lifecycle:** the session is created by `POST /v2/calls` and *exists until explicitly ended*
(completed, failed, or archived). A crash does not end a session — recovery replays the event
log and rehydrates it.

### 2.2 Turns — the unit of conversation

- **AI turn**: `message.queued → message.started (TTS begins) → message.completed (TTS done)`.
- **User turn**: `speech.started → speech.partial* → speech.final` (an "utterance").
- **Interruption**: a user speech burst starting during a `speaking` state cancels the AI turn
  (`user.interrupted`), TTS stops immediately, the new user turn proceeds.
- **Turn lease**: when the AI is "waiting for a reply", the server keeps a *turn lease* on the
  call (replaces v1's 45-second `send_message_and_wait`). A lease has **no expiry semantics for
  the conversation** — it is a status marker (`ai_wait_status`) and an event (`turn.lease`),
  cleared when the user's turn completes or the call ends. The AI *subscribes* to
  `speech.final`/`turn.ended` instead of polling.

### 2.3 Media pipeline abstraction

```
                 ┌────────────────────────────────────────────────┐
   device ◄────► │ MediaGateway (per call)                        │
   (WebRTC/SIP/  │  ┌──────────────┐   ┌───────────────┐          │
    browser)     │  │ STTProvider  │   │ TTSProvider   │          │
    raw audio ──►│  │ streamIn()   │   │ streamOut()   │◄── text  │
                 │  │ → events    │   │ stop()        │          │
                 │  └──────────────┘   └───────────────┘          │
                 │  VAD · echo cancel · jitter · mix (bridge)     │
                 └────────────────────────────────────────────────┘
```

**Interfaces** (abbreviated; full contracts in [04-api-spec.md](./04-api-spec.md) and SDK):

```typescript
interface TransportProvider {
  attach(callId: string, endpoint: string): Promise<TransportSession>;  // offer/answer
  start(callId: string): Promise<void>;
  stop(callId: string, reason: string): Promise<void>;
  onAudioFrame(cb: (chunk: AudioChunk) => void): void;   // device → platform
  sendAudio(chunk: AudioChunk): void;                     // platform → device
  isAlive(callId: string): boolean;                       // heartbeat
}

interface SttProvider {
  startUtterance(callId: string, config: SttConfig): void;
  feed(callId: string, chunk: AudioChunk): void;
  stopUtterance(callId: string): Promise<FinalUtterance>; // emit speech.final
  abort(callId: string): void;
  /* emits: speech.started / speech.partial / speech.final / speech.failed */
}

interface TtsProvider {
  speak(callId: string, utterance: QueuedMessage): AsyncGenerator<AudioChunk>;
  stop(callId: string): Promise<void>;                    // barge-in, < 50ms cut
  /* emits: message.started / message.completed */
}
```

Providers are registered by name and chosen per call:

```jsonc
{ "stt": { "provider": "on-device" }, "tts": { "provider": "on-device" } }
{ "stt": { "provider": "deepgram", "language": "en" },
  "tts": { "provider": "elevenlabs", "voice": "peter" } }
```

**Free-tier default:** `on-device` STT/TTS (Android SpeechRecognizer / TextToSpeech, iOS
Speech/AVSpeechSynthesizer) — exactly the $0 path that exists today. Cloud providers are
optional adapters that never block the core.

### 2.4 Event Plane

`StreamAdapter` interface with three implementations:

| Adapter | Latency | Scale | Cost | Use |
|---------|---------|-------|------|-----|
| `InProcessBus` (exists today as `DefaultEventBus`) | <1ms | 1 process, ~1k calls | $0 | default dev/small prod |
| `RedisStreams` (Valkey) | ~1-5ms | multi-worker, thousands of calls | $0 self-host / free tier | primary scale path |
| `NATS`/`Kafka` | ~1-10ms | very large | infra cost | optional large deployments |

The adapter interface is the *only* touchpoint — business logic never knows which plane is
underneath. Events are written to the durable **event log first** (outbox pattern), then
published; a publisher failure never loses an event.

---

## 3. Component diagram (D2)

### 3.1 Node-level components

```
┌─────────────────────────────────── API Gateway / Worker (Node process) ────────────────────────────┐
│                                                                                                    │
│  ┌───────────────┐  ┌───────────────┐  ┌────────────────┐  ┌───────────────────────────────────┐   │
│  │ REST router   │  │ MCP endpoint  │  │ WS/SSE server  │  │ Compatibility façade             │   │
│  │ /api/v2 · /v1│  │ /mcp (v1+v2)  │  │ /v2/events ·   │  │ maps v1 REST + /phone WS + MCP    │   │
│  │               │  │               │  │ /v2/media      │  │ tool names onto v2 services       │   │
│  └───────┬───────┘  └───────┬───────┘  └───────┬────────┘  └──────────────────┬────────────────┘   │
│          │  AuthN/Z · ownership · idempotency · rate limit · Zod · audit      │                    │
│  ┌───────▼──────────────────▼──────────────────▼───────────┐  ┌──────────────▼────────────────┐   │
│  │                    Command & Query Bus                   │  │  Event Dispatcher (fans out  │   │
│  │    commands: validate → FSM guard → apply → emit        │  │  to SSE, WS, MCP notifications,│   │
│  └───────┬─────────────────────────────────────────────────┘  │  webhooks, metrics)            │   │
│  ┌───────▼─────────────────────────────────────────────────┐  └──────────────┬────────────────┘   │
│  │ CallService (FSM core, session aggregate)               │                 │                    │
│  └───────┬───────────────────┬─────────────────────────────┘                 │                    │
│  ┌───────▼──────────────┐  ┌──▼──────────────────────────┐   ┌──────────────▼────────────────┐   │
│  │ TurnCoordinator      │  │ ToolInvoker                 │   │ SubscriptionManager          │   │
│  │ leases · barge-in ·  │  │ function_call.requested →   │   │ per-consumer cursors · dedupe│   │
│  │ silence · turn state │  │ completed · retries/rollback│   │ · resume tokens · replay     │   │
│  └───────┬──────────────┘  └──┬──────────────────────────┘   └──────────────┬────────────────┘   │
│  ┌───────▼────────────────────▼───────────────────────────┐                  │                    │
│  │ MediaGateway (transport registry · STT/TTS registry)   │                  │                    │
│  └───────┬────────────────────────────────────────────────┘                  │                    │
│          │                                                                   │                    │
│  ┌───────▼───────────────────────────────────────────────────────────────────▼────────────────┐  │
│  │ EventPlane (StreamAdapter) — InProcessBus | RedisStreams | NATS/Kafka                        │  │
│  └───────┬────────────────────────────────────────────────────────────────────────────────────┘  │
│  ┌───────▼────────────────────────────────────────────────────────────────────────────────────┐  │
│  │ Durable stores: Postgres (event_log, sessions_view, transcripts, tools, callbacks, media,   │  │
│  │ recordings, audit, ai_keys, phone_tokens) · Valkey/Redis cache (optional) · S3/minio (rec.)  │  │
│  └────────────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                                    │
│  Cross-cutting: logger (pino) · metrics (Prometheus exporter) · tracing (correlation_id) ·        │
│                 sweeper · recovery manager · db health monitor · secrets (env / secret manager)    │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Process topology (scale-out)

```
                    ┌────────────┐
    clients ───────►│ Gateway    │  stateless: auth · routing (call_id → worker) · rate limits
                    └─────┬──────┘
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   ┌────────────┐  ┌────────────┐  ┌────────────┐     worker i owns calls hash(call_id)%N
   │ Worker 0   │  │ Worker 1   │  │ Worker N   │     holds FSM + media for its calls
   └─────┬──────┘  └─────┬──────┘  └─────┬──────┘
          └───────────────┼───────────────┘
                          ▼
             ┌────────────────────┐  ┌────────────────────┐
             │ Postgres           │  │ Redis Streams /    │
             │ (event log = truth)│  │ Valkey (events +   │
             └────────────────────┘  │ session cache)     │
                                     └────────────────────┘
```

- **Gateway** is stateless → horizontal scaling is trivial; workers are sticky-routed per call.
- **Failover:** worker crash → event log replay + rehydrate session on another worker
  (RTO target < 5s, RPO 0; see [08-reliability-ops.md](./08-reliability-ops.md) §3).
- **Single-instance mode** (the $0 default): gateway + worker in one process, in-process bus —
  everything above degrades gracefully to today's deployment.

---

## 4. Command/query surface (what the components expose)

Full specs in [04-api-spec.md](./04-api-spec.md). Shape only, here:

```typescript
// Commands (idempotent, validated, emit events)
createCall(input: CreateCallInput): Promise<CallRef>            // → call.created
answerCall(callId, {provider})                                  // → call.connected
hangupCall(callId, {outcome, result})                           // → call.completed | call.failed
pauseCall(callId, {until, note}) / resumeCall(callId)           // → call.paused / call.resumed
sendMessage(callId, {content, tts})                             // → message.queued → … → completed
speak(callId, {content})                                        // streaming TTS variant
stopSpeaking(callId)                                            // → user.interrupted? no: turn.cancelled
submitUtterance(callId, {text, idempotencyKey})                 // → speech.final
reportDtmf(callId, {digit})                                     // → dtmf.received
invokeTool(callId, {tool, args, invocationId})                  // → function_call.completed
transfer(callId, {target})                                      // → call.transfer.requested
archiveCall(callId)                                             // → call.archived

// Queries
getCall(callId) · getTranscript(callId, {since, partials}) · listCalls(filter)
subscribeEvents(callId, {cursor}) → SSE/WS stream
getMetrics(callId) · getUsage(callId)
```

---

## 5. State machine (D4)

### 5.1 States

| State | Meaning |
|-------|---------|
| `idle` | Initial/terminal base; a session before creation or after archive |
| `creating` | Call resource being persisted (brief, fail-fast) |
| `ringing` | Device notified; waiting for human answer (policy-controlled, no hard cap) |
| `connecting` | Media negotiation (offer/answer, ICE) in progress |
| `connected` | Media established; conversation active |
| `listening` | Sub-state: human is expected/being heard (STT armed) |
| `thinking` | Sub-state: AI turn in flight (turn lease held, TTS not yet started) |
| `speaking` | Sub-state: TTS streaming to device |
| `paused` | Conversation suspended (callback scheduled, hold) |
| `transferring` | Call being handed to another agent/queue |
| `ending` | Teardown in progress (finalize transcript, release media) |
| `completed` | Terminal: resolved with an outcome |
| `failed` | Terminal: unrecoverable error, reason recorded |
| `archived` | Terminal: retention policy applied, data archived/exported |

`listening`/`thinking`/`speaking` are **sub-states of `connected`** — modeled as
`connected.listening`, `connected.thinking`, `connected.speaking` in code (single field with
`phase`), but shown separately here for clarity.

### 5.2 Diagram

```
                      ┌──────────────────────────┐
                      ▼                          │  archiveCall()
┌──────┐    create   ┌──────────┐   persist ok   ▼
│ idle │────────────►│ creating │───────────►┌────────┐
└──────┘             └──────────┘            │ ringing │
      ▲                                      └───┬────┘
      │                                         │ answer
      │                                         ▼
      │                                    ┌─────────┐   media    ┌───────────────────────────┐
      │   archive / delete                │connecting├───────────►│        connected           │
      │                                    └─────────┘            │ ┌─────────┐ ┌───────────┐ │
      │                                                            │ │ listening│ │ thinking  │ │
      │     ┌──────────────┐                                       │ └────▲────┘ └─────▲─────┘ │
      │     │  archived    │  retention                         │      │            │       │
      │     └──────────────┘                                    │      │ speech    │ turn    │
      │            ▲                                            │      │ final    │ started │
      │            │                                            │ ┌────┴────────────────┴──┐   │
      │   ┌────────┴───────┐     error         ┌─────────┐      │ │        speaking        │   │
      └───│   completed    │◄──────────────────│  ending │      │ └───────────┬────────────┘   │
          └────────────────┘                   └────┬────┘      │             │ barge-in       │
              ▲                            hangup   │            │             ▼                │
              │                                     │            │        listening (new turn) │
              │        ┌──────────────┐             │            └─────────────────────────────┘
              │        │    failed    │◄── unrecoverable error
              │        └──────────────┘
              │              │
              │              └──────────────► (archivable too)
              │
              │  pause            resume/answer
   connected ◄┼──────────────────────────────┐
              └────────────► ┌────────┐ ─────┘
                            │ paused  │
                            └────────┘
```

(Also: `transferring` is a transient sub-state of `connected`; on success the call stays
`connected` with a new `agent_id`, on failure it returns to `connected` and `call.transfer.failed`
is emitted.)

### 5.3 Transition table (exhaustive for v2 core)

| # | From | Event / Command | Guard | To | Emits |
|---|------|----------------|-------|-----|-------|
| 1 | idle | createCall | agent+user valid, rate ok | creating | — |
| 2 | creating | persisted | — | ringing | `call.created` |
| 3 | creating | persist error | — | failed | `call.failed` (reason=persistence) |
| 4 | ringing | answerCall | device authorized for call | connecting | `call.ringing.end` (optional), `call.answer.requested` |
| 5 | ringing | ring policy (no answer, AI-side cancel) | policy | completed/failed | `call.noanswer` / `call.failed` |
| 6 | connecting | media established | transports alive | connected(listening) | `call.connected` |
| 7 | connecting | media fail | retries exhausted | failed | `call.failed` (reason=media) |
| 8 | connected.listening | user speech starts | — | connected.listening (phase stays) | `speech.started` |
| 9 | connected.listening | speech.final | — | — | `speech.final`, `transcript.updated`, `turn.ended`(user) |
| 10 | connected | silence policy fires | configurable threshold | — | `silence.detected` (AI decides; state unchanged) |
| 11 | connected.listening | AI sends message | turn allowed | connected.thinking | `message.queued` |
| 12 | connected.thinking | TTS begins | — | connected.speaking | `message.started` |
| 13 | connected.speaking | TTS completes | — | connected.listening | `message.completed`, `turn.ended`(ai) |
| 14 | connected.speaking | user speech starts (barge-in) | — | connected.listening | `user.interrupted`, `turn.cancelled`(ai); TTS hard-stop |
| 15 | connected | pauseCall | — | paused | `call.paused` |
| 16 | paused | resumeCall / answerCall | — | connected.listening | `call.resumed` |
| 17 | paused | callback timer fires | — | ringing (re-ring) | `call.ringing` |
| 18 | connected | transferCall | target valid | transferring | `call.transfer.requested` |
| 19 | transferring | transfer accepted | — | connected(listening) | `call.transfer.completed` |
| 20 | transferring | transfer rejected/fail | — | connected(listening) | `call.transfer.failed` |
| 21 | connected/paused | hangupCall {outcome} | — | ending | `call.ending` |
| 22 | ending | teardown done | — | completed | `call.completed` |
| 23 | any | unrecoverable error | — | failed | `call.failed` |
| 24 | completed/failed | archiveCall | retention policy | archived | `call.archived` |

**Enforcement:** every command path calls `fsm.transition(from, to, guard, payload)`; invalid
transitions throw `INVALID_TRANSITION` (a 409 in REST). The FSM is pure and unit-testable;
guards are side-effect-free functions; event emission happens only after a transition commits
(emit-after-commit, outbox).

---

## 6. Failure semantics (summary)

Full treatment in [08-reliability-ops.md](./08-reliability-ops.md) §3.

- Commands are **idempotent** (Idempotency-Key / client_message_id) → retries are safe.
- Events are **at-least-once**; consumers dedupe by `event_id`.
- Every command is an **outbox write + publish** — no publish-only paths exist.
- Crash recovery: replay event log → rehydrate sessions → rebuild timers (extends v1
  RecoveryManager pattern).
- Media: heartbeat (RTCP + app ping), auto-reconnect with backoff, jitter buffer, renegotiation.
- Notification delivery: per-user queue with flush-on-reconnect (exists in v1, formalized).

---

## 7. Observability surface

- **Metrics (Prometheus):** active calls by state, event throughput, STT/TTS latency histograms,
  end-to-end turn latency, dropped calls, reconnects, API usage per identity, tool usage,
  transcript quality score (finalization rate, partial churn).
- **Logs:** pino structured; every event logged with `correlation_id`; no PII by default.
- **Tracing:** `correlation_id` per call, `causation_id` chains; optional OpenTelemetry export.
- **Dashboards:** Grafana — see [docs/GRAFANA_DASHBOARDS.md](../GRAFANA_DASHBOARDS.md) for the
  v1 iteration; v2 adds per-state call gauges and STT/TTS provider breakdowns.

---

## 8. Related v1 components reused (not rewritten)

| v1 component | v2 role |
|--------------|---------|
| `DefaultEventBus` (+ hooks, registry) | base of `InProcessBus` adapter |
| Session/callback repositories (4 modes) | become the Postgres store layer behind v2 schema |
| RecoveryManager / LifecycleCoordinator / sweeper | retained, event-log-based |
| phone-tokens / ai-keys | retained, extended (scopes, rotation) |
| Ownership checks (`checkCallOwnership`, `authorizeCall`) | retained for all v2 endpoints |
| Fastify error format `{error, code, details}` | unchanged |
| Android SignalingClient / retry queues | evolve into v2 device SDK transport |