# 03 — Event Model

> **Deliverable:** 5 (event model)

## 1. Envelope

Every event, on every transport (event log, in-process bus, SSE, WS, Redis Streams), has one
shape:

```jsonc
{
  "id":            "01J2Z8X…",              // event_id — UUID v7, globally unique, sortable
  "type":          "speech.final",          // dotted domain name
  "version":       1,                        // schema version of `payload`
  "call_id":       "…",                      // always present (scoped aggregate)
  "correlation_id":"…",                      // same for the whole call lifetime
  "causation_id":  "…",                      // id of the event that caused this one
  "occurred_at":   "2026-08-04T12:00:00.000Z",
  "sequence":      142,                      // per-call monotonic seq (event log)
  "stream_key":    "calls:01J2…",            // partition key (Redis/NATS scaling)
  "actor":         { "type": "ai" | "user" | "system" | "device",
                     "identity": "agent-01" },  // who caused it (audit + authz)
  "payload":       { … }                      // type-specific, zod-validated
}
```

**Contract:** `id` is unique (dedupe key for consumers). `sequence` is contiguous per call in
the event log — gaps are a corruption signal. `causation_id` enables tracing
(message.queued ← message.started ← …).

## 2. Delivery semantics

- **At-least-once.** Consumers must dedupe by `id` (see SubscriptionManager contract in
  [04-api-spec.md](./04-api-spec.md) §3).
- **Ordering:** strictly per-call per-event-type; total per-call order in the event log.
- **Retention:** event log kept for 90 days by default; archived per policy after.
- **Replay:** any consumer may replay from `Last-Event-ID` / cursor (SSE `Last-Event-ID`,
  WS `resume` frame, REST `GET /events?after=`).

## 3. Event catalog

### 3.1 Lifecycle events

| Event | v1 equivalent | Payload highlights | Emitted when |
|-------|---------------|--------------------|--------------|
| `call.created` | `call.created` (internal) | `user_id, agent_id, reason, summary, context, media:{stt,tts,transport}` | session persisted |
| `call.ringing` | `call_incoming` (WS) | `provider, ring_policy` | device notified |
| `call.noanswer` | — | `waited_ms, policy` | ring policy fired (AI decides next) |
| `call.answer.requested` | — | `provider, device` | human taps answer |
| `call.connected` | `call_answered` | `connected_at, provider, ice_state` | media established |
| `call.hold` | — | `reason` | hold started (optional audio) |
| `call.paused` | `callback_scheduled` | `resume_at, note` | pause accepted |
| `call.resumed` | `call_resumed` | `resume_at` | conversation continues |
| `call.transfer.requested` | — | `target, reason` | transfer initiated |
| `call.transfer.completed` | — | `target, agent_id` | new agent owns conversation |
| `call.transfer.failed` | — | `target, reason` | transfer rolled back |
| `call.ending` | — | `reason` (hangup/error/timeout) | teardown starts |
| `call.completed` | `call.ended` | `outcome{decision, selected_option, sentiment, action_items}, duration_ms` | terminal, resolved |
| `call.failed` | — | `reason, code, attempts` | terminal, unrecoverable |
| `call.archived` | — | `archive_uri, retention_days` | retention applied |

### 3.2 Speech & transcript events (the real-time heart)

| Event | Payload highlights | Notes |
|-------|--------------------|-------|
| `speech.started` | `utterance_id, speaker="user"` | VAD fires |
| `speech.partial` | `utterance_id, text, confidence, start_ms, end_ms(0=open)` | streamed while talking; replaces previous partial for same utterance |
| `speech.final` | `utterance_id, text, confidence, language, start_ms, end_ms, duration_ms` | end-of-utterance; final answer |
| `speech.failed` | `utterance_id, reason` | STT error; partials discarded per policy |
| `transcript.updated` | `segment {seq, role, text, start_ms, end_ms, confidence}` | append-only delta, one per finalized segment |
| `transcript.partial.cleared` | `utterance_id` | superseded partial removed from live view |

### 3.3 AI turn / message events

| Event | Payload highlights | Notes |
|-------|--------------------|-------|
| `message.queued` | `message_id, content, tts_config, reply_to` | AI message accepted (turn lease held) |
| `message.started` | `message_id, tts_provider, streamed=true` | first audio byte boundary |
| `message.completed` | `message_id, duration_ms, chars_spoken, audio_bytes` | TTS finished naturally |
| `message.failed` | `message_id, reason, partial_audio_ms` | TTS/provider error |
| `turn.lease` | `ai_wait_status{active, active_until, last_active_at}` | AI waiting for human (no expiry semantics) |
| `turn.cancelled` | `turn_type:"ai", message_id, reason:"barge_in"` | AI turn killed mid-flight |
| `turn.ended` | `turn_type:"ai"|"user", turn_id` | turn resolved (polling replacement) |

### 3.4 Interruption & silence

| Event | Payload highlights | Notes |
|-------|--------------------|-------|
| `user.interrupted` | `interrupted_message_id, interrupted_audio_ms, utterance_id` | **barge-in**: TTS already stopped; AI should cancel generation |
| `silence.detected` | `after_ms, context:"post_question"|"mid_turn"|"post_message", count` | advisory; **AI decides** (continue / prompt / end) |
| `call.noactivity` | `silent_seconds, silence_count` | escalation after N silences |

### 3.5 Input events

| Event | Payload highlights | Notes |
|-------|--------------------|-------|
| `dtmf.received` | `digit, sequence` | keypad input; useful for IVR-style flows |
| `media.event` | `type: "muted"|"unmuted"|"volume"` | device state |

### 3.6 Tool / function call events

| Event | Payload highlights | Notes |
|-------|--------------------|-------|
| `function_call.requested` | `invocation_id, tool_name, args, timeout_ms` | AI-initiated only |
| `function_call.completed` | `invocation_id, tool_name, result, latency_ms, ok` | result delivered to AI |
| `function_call.failed` | `invocation_id, tool_name, reason, attempts` | retries per policy |

### 3.7 Media / connection health events

| Event | Payload highlights | Notes |
|-------|--------------------|-------|
| `media.connected` | `provider, transport_type` | |
| `media.disconnected` | `reason, last_packet_ms` | device link lost |
| `media.reconnecting` | `attempt, backoff_ms` | auto-reconnect started |
| `media.reconnected` | `gap_ms` | conversation continues |
| `quality.metric` | `rtt_ms, jitter_ms, packet_loss_pct, mos_proxy` | throttled (1/s per call) |

### 3.8 Observability events (internal, not exposed to AI subscribers by default)

| Event | Payload highlights |
|-------|--------------------|
| `usage.metric` | `tokens_in, tokens_out, stt_seconds, tts_seconds, provider` |
| `latency.metric` | `phase, ms` (stt.partial, stt.final, tts.ttfb, turn.e2e) |
| `audit.event` | `actor, action, resource, before, after, ip` |

## 4. Subscriptions (who may subscribe to what)

| Subscriber | Default scope | Notes |
|------------|---------------|-------|
| AI identity (owner of the call) | everything except `usage.metric`/`audit.event` | must pass ownership check |
| Human device | speech.*, message.*, call.*, media.* | never sees `function_call.*` args/output (privacy + injection surface) |
| Dashboard/ops | everything, including observability | internal token class |

Scopes are declarative in the subscription token (`scope: "ai:call"`, `scope: "device:call"`,
`scope: "ops"`).

## 5. Event type registration

Every type is registered in a single `EventRegistry` with:
- zod schema of `payload` (validated at emit time in dev, logged-and-continue in prod),
- version,
- consumer routing (EventPlane),
- `retention` hint.

New event types are additive; payload changes bump `version` and, where incompatible, are
emitted under a new type name (e.g. `speech.final.v2`) during transition, then the old one is
deprecated per the deprecation policy in [07-migration-plan.md](./07-migration-plan.md) §4.

## 6. Mapping to v1 events

| v1 | v2 |
|----|----|
| WS `call_incoming` | `call.created` → device projection `call.ringing` |
| WS `ai_message` | `message.completed` (plus queued/started in v2) |
| WS `call_ended` / `call_cancelled` | `call.completed` / `call.failed`(reason=cancelled) |
| WS `callback_scheduled` | `call.paused` (with `resume_at`) |
| WS `ai_wait_status` | `turn.lease` (same payload shape) |
| WS `connected` | `media.connected` (device projection) |
| internal `call.created/answered/paused/ended/cancelled/resumed/deleted/expired` | identical names, versioned envelopes |