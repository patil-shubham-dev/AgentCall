# 02 — Sequence Diagrams

> **Deliverable:** 3 (sequence diagrams)

All diagrams are ASCII. Notation: `A ──► B` = async message, dashed = event on the EventPlane,
`|-- x --|` = duration. Times are targets from [08-reliability-ops.md](./08-reliability-ops.md) §2.

---

## 2.1 Call creation → ring → answer → connect

```
 AI/SDK            Gateway         CallService     EventPlane      Device (human)
  │ createCall()      │               │               │               │
  │──────────────────►│               │               │               │
  │  (Idempotency-Key)│  validate→FSM │               │               │
  │                   │──────────────►│               │               │
  │                   │               │ write event   │               │
  │                   │               │──────────────►│ call.created  │
  │                   │               │               │───► (log + subscribers)
  │                   │               │               │──────────────►│ call_incoming (ring)
  │ 201 {call_id,     │               │               │               │
  │ events_url} ◄─────│               │               │               │
  │                   │               │               │               │
  │                   │               │               │               │ user answers
  │                   │  answerCall() │               │               │◄── tap answer
  │                   │──────────────►│ FSM ringing→connecting          │
  │                   │               │──────────────►│ call.answer.requested
  │                   │  create media offer                            │
  │                   │═══════════════╪═══════════════╪═══════════════►│ negotiate (SDP/ICE)
  │                   │  media established │          │               │
  │                   │               │ FSM connecting→connected       │
  │                   │               │──────────────►│ call.connected │
  │  event ◄──────────│───────────────│───────────────┤               │
  │                   │               │               │──────────────►│ call_connected
```

Key properties: no polling anywhere; the AI learns `call.connected` by subscription; ring
duration is policy-driven (events only).

---

## 2.2 AI turn with streaming TTS (the v1 `send_message` replacement)

```
 AI/SDK              TurnCoordinator    TTS provider      Device           EventPlane
  │ sendMessage()        │                │                 │                 │
  │─────────────────────►│  validate,     │                 │                 │
  │                      │  lease acquire │                 │                 │
  │                      │────────────────│                 │                │ message.queued
  │                      │  FSM: listening→thinking         │                 │
  │                      │  stream text to TTS              │                 │
  │                      │────────────────►│                │                 │
  │                      │                 │─first audio────►│── plays ──►    │
  │                      │                 │ (TTFB < 500ms)  │                │ message.started
  │                      │                 │─chunks─────────►│                │ (streamed)
  │                      │                 │ (token-synced)  │                │
  │                      │                 │  done           │                │
  │                      │◄────────────────│                 │                │
  │                      │  lease release, FSM: speaking→listening            │
  │                      │───────────────────────────────────│               │ message.completed
  │  ← msg.completed ────│───────────────────────────────────│               │ turn.ended
```

The AI never waits; it receives `message.completed` and `turn.ended` when speaking finishes.

---

## 2.3 User turn with streaming STT (partials)

```
 Device              STT provider       TurnCoordinator       EventPlane        AI/SDK
  │ user starts talking│                   │                    │                 │
  │──audio frames─────►│  VAD fires        │                    │                 │
  │                    │──────────────────►│                   │ speech.started   │
  │                    │                   │───────────────────►│ ─────────────────►│
  │                    │ partial1          │                    │                 │
  │                    │──────────────────►│───────────────────►│ speech.partial   │
  │                    │ partial2          │                    │ (id, text,       │
  │                    │──────────────────►│───────────────────►│  confidence,     │
  │                    │                   │                    │  start_ms)       │
  │  pause > endpoint  │  (VAD end)        │                    │                 │
  │  (no more frames)  │──────────────────►│  finalize          │                 │
  │                    │                   │───────────────────►│ speech.final     │
  │                    │                   │  append transcript  │ transcript.updated│
  │                    │                   │  clear turn lease   │ turn.ended(user) │
  │                    │                   │───────────────────►│ ─────────────────►│
  │                    │                   │                    │  (AI responds;   │
  │                    │                   │                    │   see 2.2)       │
```

The AI receives `speech.partial` *while the human is still talking* — it may pre-fetch tool
context, but must not begin an AI turn until `speech.final` (barge-in rules, §2.4).

---

## 2.4 Barge-in (interruption)

```
 Device            MediaGateway        TTS provider       TurnCoordinator      EventPlane      AI/SDK
  │ AI speaking: "Today I'd like to..." │                    │                   │               │
  │◄──── audio frames (AI TTS) ────────│                    │                   │               │
  │ user: "No."                         │                    │                   │               │
  │── user frames ──────────────────────────────────────────►│                   │               │
  │                    │ VAD detects speech during speaking phase                │               │
  │                    │───────────────────────────────────►│  barge-in = true   │               │
  │                    │ TTS.stop() (hard cut < 50ms)       │                   │               │
  │                    │──────────────────►│ stops           │                   │               │
  │                    │                   │                 │ user.interrupted │               │
  │                    │                   │                 │ turn.cancelled(ai)│               │
  │                    │                   │                 │──────────────────►│ ← event pair  │
  │                    │ FSM: speaking→listening             │ speech.started    │               │
  │                    │───────────────────────────────────►│──────────────────►│ user turn 2.3 │
  │                    │                   │                 │                   │               │
```

AI contract on `user.interrupted`: cancel any in-flight generation, drop partial TTS state; the
next `speech.final` continues the conversation naturally. `turn.cancelled` carries the id of
the killed AI message for reconciliation.

---

## 2.5 Silence handling (replaces the timeout)

```
 Device          TurnCoordinator         EventPlane       AI/SDK
  │ ... silence after speech.final ...     │                 │
  │──────────────────────────────────────►│  silence timer   │
  │   (configured: 5s default, per call)   │  armed           │
  │                                       │                 │
  │ 5s pass, no speech                    │                 │
  │                                       │ silence.detected │
  │                                       │──────────────────►│  AI decides:
  │                                       │                 │  • continue waiting (no-op)
  │                                       │                 │  • send "Are you still there?"
  │                                       │                 │  • hangupCall()
  │  (if user speaks first, silence timer cancels;          │
  │   no event is emitted)                │                 │
```

Escalation: after `N` consecutive `silence.detected` with no user activity, the platform emits
`call.noactivity` (policy-driven) — still advisory, still the AI's decision. **There is no
hard-coded conversation timeout in the platform.**

---

## 2.6 Tool invocation during a call (transparent to the human)

```
 AI/SDK            ToolInvoker        EventPlane         Tool impl (calendar, db, ...)
  │ invokeTool(call_id, tool, args)     │                    │                    │
  │────────────────────────────────────►│  FSM ok (any       │                    │
  │                                     │  connected phase)  │                    │
  │                                     │ function_call.requested (invocation_id)
  │                                     │───────────────────►│───────────────────►│
  │                                     │                    │ executes (no audio │
  │                                     │                    │ interruption; AI   │
  │                                     │                    │ stays in thinking) │
  │                                     │                    │────────── result ─►│
  │                                     │ function_call.completed (id, result,   │
  │                                     │   latency_ms, ok/err)                  │
  │                                     │───────────────────►│───────────────────►│
  │                                     │                    │                    │
  │  (AI then sends its next message with the result — the human hears only the  │
  │   natural next sentence; tool work is invisible)                             │
```

While `thinking`, the platform may emit `ai.working` (visual "…" on devices) — never audio
pollution. Failed invocations emit `function_call.failed` and are retried per policy or
reported to the AI.

---

## 2.7 Pause → callback → resume

```
 AI/SDK         CallService      CleanupScheduler     Device        EventPlane
  │ pauseCall(until=10m) │            │                  │              │
  │─────────────────────►│ FSM connected→paused          │              │
  │                      │───────────────────────────────│ call.paused  │
  │                      │──────────────────────────────►│ (hold tone)  │
  │                      │ schedule timer ─────────────►│              │
  │                      │            │                  │              │
  │  (10m later)         │◄────────────│ timer fires      │              │
  │                      │ FSM paused→ringing            │              │
  │                      │───────────────────────────────│ call.ringing │
  │                      │──────────────────────────────►│ (re-ring)    │
  │                      │            │                  │ user answers │
  │                      │───────────────────────────────│ call.resumed │
  │ event ──────────────►│──────────────────────────────►│─────────────►│
```

Timer reconstruction after a crash is handled by the RecoveryManager (existing v1 pattern,
extended to all v2 timers).

---

## 2.8 Crash recovery (RTO < 5s, RPO 0)

```
 Worker A (dies)            Postgres (event log)        Worker B (replacement)
  │  ... call c1 in speaking phase ...                      │
  │  ✗ process killed (no graceful shutdown)                │
  │                                                         │
  │       │ startup: recoveryManager.replay()               │
  │       │◄──────── SELECT events WHERE call_id=c1         │
  │       │──────── replay call.created…speech.final…       │
  │       │   → rehydrate session state (FSM=connected)     │
  │       │   → detect media dropped; mark transport dead   │
  │       │   → emit call.reconnecting (subscribers learn)  │
  │       │   → device SDK auto-reconnects (backoff 3s)     │
  │       │   → media renegotiation on reconnect            │
  │       │   → call.reconnected; conversation continues    │
  │                                                         │
  │  Human experience: ≤ 5s audio gap, conversation intact. │
```

---

## 2.9 Legacy `send_message_and_wait` mapped onto v2 (compatibility)

```
 MCP client (v1 tool)          Compatibility façade           v2 internals
  │ send_message_and_wait()          │                             │
  │─────────────────────────────────►│  create turn lease (no 45s  │
  │                                  │  server cap; configurable   │
  │                                  │  client-facing window only) │
  │                                  │  sendMessage() ────────────►│ message.queued → started → completed
  │                                  │  subscribeEvents() ────────►│ speech.final / turn.ended(user)
  │                                  │◄──────── (event) ──────────│
  │  returns {outcome:"reply", ...}  │                             │
  │◄─────────────────────────────────│  (or outcome:"timeout" only  │
  │                                  │   if client window expired; │
  │                                  │   call remains active —     │
  │                                  │   AI can continue)          │
```

The v1 tool's behavior contract (reply / timeout / call_ended) is preserved exactly — but the
implementation is now event-driven; the 45s value becomes a *configurable client window*,
defaulting to `0` (no cap) for SDK-native consumers. Long conversations simply work.