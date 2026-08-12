# 04 — API Specification & Wire Protocols

> **Deliverables:** 6 (API specification), 8 (WebSocket/SSE protocol)
> **Companion docs:** [03-event-model.md](./03-event-model.md) (event payloads), [06-sdk-design.md](./06-sdk-design.md)

---

## §1. Conventions

- **Base URLs:** `/api/v2` (REST), `wss://host/v2/events`, `wss://host/v2/media`. v1 remains on
  `/api/v1` + `/phone` unchanged (compatibility façade, [07-migration-plan.md](./07-migration-plan.md)).
- **Auth:** `Authorization: Bearer <token>` — service token, AI key, or device token
  (classes in [09-security-review.md](./09-security-review.md) §3). WebSocket uses the same
  header or `?token=` for legacy clients.
- **Errors:** unchanged shape `{ error, code, details?, request_id }`. New codes:
  `INVALID_TRANSITION` (409), `IDEMPOTENCY_REPLAY` (200 with stored response), `SESSION_LOCKED`
  (423, brief), `EVENT_NOT_FOUND` (404), `QUOTA_EXCEEDED` (429).
- **Idempotency:** every mutating endpoint accepts `Idempotency-Key` (UUID). Server stores the
  first response per `(identity, key, call_id)` for 24h; replays return the stored response
  with header `X-Idempotent-Replay: true`. Existing `client_message_id` semantics (user-text)
  continue to work and map onto the same mechanism.
- **Validation:** Zod schemas at every boundary (project convention).
- **Rate limits:** per-identity sliding window, per endpoint class (see §4).

---

## §2. REST API v2

### 2.1 `POST /api/v2/calls` — create a call (session)

```jsonc
// request
{
  "user_id": "user_123",                       // human identity (device will ring)
  "agent_id": "agent-01",                      // AI identity (owner; default: caller's identity)
  "reason": "clarification",                   // clarification|approval|error|input_required|free_form
  "summary": "Need a decision on refund",      // what the AI wants
  "context": { "task_id": "t-9", "options": ["refund","credit"], "custom": { } },
  "media": {
    "transport": "auto",                       // auto|webrtc|sip|mobile|browser|desktop
    "stt":  { "provider": "on-device", "language": "en-IN" },
    "tts":  { "provider": "on-device", "voice": "default" }
  },
  "policy": {
    "ring_timeout_ms": 30000,                  // no hard cap: configurable; 0 = ring forever (advisory only)
    "silence_after_ms": 5000,                  // → silence.detected
    "no_answer_action": "keep_ringing"         // keep_ringing|fail|voicemail
  },
  "priority": "normal"                          // low|normal|high|urgent
}
```

```jsonc
// 201
{
  "call_id": "01J2…",
  "status": "ringing",
  "events_url": "/api/v2/calls/01J2…/events",   // SSE subscription (resumable)
  "ws_events_url": "wss://host/v2/events?call_id=01J2…&token=…",
  "media_url": "wss://host/v2/media?call_id=01J2…&token=…",
  "created_at": "…"
}
```

Notes: `summary`/`reason` optional in v2 (free-form conversations); `agent_id` must match the
calling identity unless it is a service token (ownership model carried from v1).
`policy` values are advisory timers — **no hard conversation timeout exists**.

### 2.2 `GET /api/v2/calls/:callId` — session snapshot

```jsonc
{
  "call_id": "…", "status": "connected", "phase": "speaking",
  "fsm_version": 14, "user_id": "…", "agent_id": "…",
  "transcript_seq": 41, "active_turn": { "type": "ai", "message_id": "…" },
  "ai_wait": { "active": true, "active_until": "…", "last_active_at": "…" },
  "media": { "transport": "mobile", "stt": "on-device", "tts": "on-device", "connected": true },
  "created_at": "…", "answered_at": "…", "ended_at": null,
  "context": {…}, "result": null
}
```

### 2.3 Call control

| Endpoint | Body | Effect / events |
|----------|------|-----------------|
| `POST /calls/:id/answer` | `{ "provider": "mobile" }` | ring→connecting→connected; `call.answer.requested`, `call.connected` |
| `POST /calls/:id/hangup` | `{ "outcome": { "decision": "approved", "selected_option": "credit", "sentiment": "positive", "action_items": […] }, "note": "…" }` | → `ending`→`completed`; `call.ending`, `call.completed`. (Replaces `complete_call`; `cancel_call` = hangup with `outcome.decision:"cancelled"`) |
| `POST /calls/:id/pause` | `{ "until_ms": 600000, "note": "…" }` | `call.paused`; timer |
| `POST /calls/:id/resume` | `{}` | `call.resumed` |
| `POST /calls/:id/transfer` | `{ "target": "agent-02", "reason": "handoff" }` | `call.transfer.requested/completed/failed` |
| `DELETE /calls/:id` | — | archive; `call.archived` (only from completed/failed) |

All are idempotent. `hangup` is *the* terminal command — one path for every ending reason.

### 2.4 Messaging (AI → human)

| Endpoint | Body | Effects |
|----------|------|---------|
| `POST /calls/:id/messages` | `{ "content": "…", "tts": { "provider": "on-device", "voice": "…" }, "reply_to": "utt_1" }` | `message.queued` → `message.started` → `message.completed` (or `message.failed`) |
| `POST /calls/:id/speak` | same; **streaming** via SSE response or WS | TTS begins on first token; returns `message_id` immediately |
| `POST /calls/:id/stop-speaking` | `{}` | cancels current AI turn (AI-initiated stop, distinct from user barge-in) |

`messages` response: `{ "message_id", "status": "queued" }` — the AI never blocks on speech.

### 2.5 Input (human → AI)

| Endpoint | Body | Effects |
|----------|------|---------|
| `POST /calls/:id/utterances` | `{ "text": "…", "client_message_id": "…", "language": "en" }` | `speech.started`+`speech.final` (text-typed path; STT passthrough) — idempotent via `client_message_id` (carried from v1) |
| `POST /calls/:id/dtmf` | `{ "digit": "5" }` | `dtmf.received` |

### 2.6 Transcript

`GET /calls/:id/transcript?after=<seq>&partials=false&limit=200`

```jsonc
{
  "call_id": "…", "segments": [
    { "seq": 40, "role": "user", "type": "speech", "text": "I'd like to book…",
      "start_ms": 1200, "end_ms": 3800, "confidence": 0.94,
      "utterance_id": "utt_9", "created_at": "…" }
  ],
  "has_more": false
}
```

- `partials=true` includes open partials with `is_partial: true` (live typing view).
- Timestamps are call-relative ms; searchable via `?q=` (ILIKE on text) and `?role=`.

### 2.7 Tools

| Endpoint | Body | Effects |
|----------|------|---------|
| `POST /calls/:id/tools/:tool/invoke` | `{ "arguments": {…}, "invocation_id": "…" }` | `function_call.requested` → `completed/failed`; sync response returns result when ≤ 5s, else 202 with `invocation_id` + result via event |

### 2.8 Events (SSE)

`GET /calls/:id/events` — **the AI's real-time feed** (D8 §3).

### 2.9 Status & ops (v1-compatible)

`GET /api/v2/health`, `GET /api/v2/ready`, `GET /api/v2/metrics` — same contracts as v1.

### 2.10 Example: full AI loop with zero polling

```http
POST /api/v2/calls                     → call_id
GET  /api/v2/calls/:id/events          → SSE stream (kept open)
POST /api/v2/calls/:id/messages        {content:"Hello, how can I help?"}
# SSE:  message.queued → message.started → message.completed
# SSE:  speech.started → speech.partial* → speech.final   (human replies)
POST /api/v2/calls/:id/hangup          {outcome:{decision:"approved"}}
# SSE:  call.ending → call.completed
```

---

## §3. WebSocket / SSE protocol (D8)

### 3.1 SSE event stream (`GET /v2/calls/:id/events`)

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
X-Accel-Buffering: no          (proxy hint)
Access-Control-Expose-Headers: Last-Event-ID

event: call.connected
id: 01J2Z9A…q
data: {"id":"01J2Z9A…q","type":"call.connected","version":1,"call_id":"…",
data:  "correlation_id":"…","causation_id":"…","occurred_at":"…","sequence":5,
data:  "actor":{"type":"system"},"payload":{"connected_at":"…","provider":"mobile"}}
```

Rules:
- `event:` = event `type` (with dots). `id:` = event `id`. `data:` may be split across lines;
  clients join with `\n` (standard SSE).
- **Resume:** client sends `Last-Event-ID: <event_id>`; server replays from the first event
  after it (at-least-once + dedupe by `id`). No `Last-Event-ID` = from now, or `?after=` for
  full history.
- **Heartbeat:** `: ping` comment every 15s (keeps proxies open; no client action needed).
- **Close:** server closes with `event: stream.end` + `data: {"reason":"call_archived"}`.
- **Backpressure:** server pauses writes when a consumer is slow; drops are **never** silent —
  a `stream.resync` event with `last_persisted_id` forces the client to replay.

### 3.2 WebSocket event channel (`wss://host/v2/events`)

Query: `?call_id=<id>&token=<token>` (or `Authorization` header). Sub-protocol: `agentcall.v2.events`.

Server → client: one JSON frame per event (same envelope as SSE `data`).

Client → server frames (control):

```jsonc
{ "type": "ping" }
{ "type": "resume",  "last_event_id": "01J2Z9A…q" }
{ "type": "ack",     "event_id": "…" }              // optional flow control
{ "type": "pause" } / { "type": "resume" }          // consumer backpressure
```

Close codes: `4000` normal, `4001` unauthorized, `4002` not a call participant, `4003` rate
limited, `4004` call not found, `4005` stream superseded (another connection took over — unique
per call, monotonic), `4006` protocol violation, `4007` server restart (client should resume
with last seen id).

Heartbeat: client `ping` every ≤20s; server responds `pong` with server time; server pings
every 25s (v1 pattern retained). Missing two consecutive heartbeats ⇒ close + reconnect with
`resume`.

### 3.3 WebSocket media channel (`wss://host/v2/media`)

Query: `?call_id=<id>&token=<token>`. Sub-protocol: `agentcall.v2.media.v1`.

- **Binary frames:** audio — 20ms Opus (primary), or PCM16 at 16 kHz configurable. First frame
  carries a 4-byte header: `[version, codec, channels, flags]`.
- **JSON control frames (client → server):**

```jsonc
{ "type": "attach",   "provider": "mobile", "direction": "sendrecv" }
{ "type": "answer",   "sdp": "…", "ice": [ "candidate" ] }
{ "type": "mute" } / { "type": "unmute" }
{ "type": "detach",   "reason": "app_background" }
```

- **JSON control frames (server → client):**

```jsonc
{ "type": "offer",    "sdp": "…", "ice": […] }
{ "type": "connected", "media_started_at": "…" }
{ "type": "play",     "message_id": "…" }          // TTS audio follows as binary
{ "type": "stop",     "message_id": "…", "reason": "barge_in" }   // TTS hard stop
{ "type": "state",    "speaking": false, "listening": true }
```

- Barge-in is **client-agnostic**: device STT (or platform VAD) detects speech during `play`
  ⇒ device sends `mute`-style event or platform cuts server-side; platform guarantees
  `user.interrupted` + `stop` ≤ 50ms after detection.

### 3.4 MCP surface (commands only)

v2 keeps the MCP endpoint at `/mcp` (Streamable HTTP, unchanged) and adds tools — all
**non-blocking**:

| Tool | Replaces | v2 semantics |
|------|----------|--------------|
| `create_call` | same | same, returns `events_url` |
| `send_message` | same | non-blocking (already is) |
| `send_message_and_wait` | same | **event-driven lease**; `timeout_seconds` becomes optional client window (default: none) |
| `subscribe_to_events` | — | returns SSE `events_url` the client may open; also delivers MCP `notifications` for MCP clients that support them |
| `get_transcript` | same | + partials flag, pagination |
| `hangup_call` | `complete_call` + `cancel_call` | single terminal tool; legacy tools remain |
| `pause_call` / `resume_call` | callback endpoint | v2 semantics |
| `stop_speaking` | — | barge-out |
| `invoke_tool` | — | function calling inside a call |

Legacy tool names (`complete_call`, `cancel_call`) remain registered and map to the façade.

---

## §4. Rate limits (per identity, sliding window)

| Class | Default | Notes |
|-------|---------|-------|
| Global | 100 req/min | v1 parity |
| Commands (messages, utterances, hangup, pause…) | 60 req/min/call | burst-tolerant; token-bucket burst 20 |
| Event stream connects | 10 connects/min/call | supersession allowed |
| Media frames | 50 msg/s/conn | enforced per connection (v1 pattern) |
| `/health` `/ready` `/metrics` | 20 req/10s | unauthenticated, probe-only |
| Tool invocations | 30/min/call | configurable |

429s carry `Retry-After`. All limits are per identity *and* per call where relevant — a
busy call never blocks other calls of the same identity.