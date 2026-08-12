# AgentCall v2 — Persistent, Event-Driven Voice Platform

> **Status:** Design document set — complete, all 18 deliverables shipped (v2.0 draft)
> **Owner:** Lead Architect
> **Scope:** Full redesign of AgentCall into a persistent, event-driven voice platform that any LLM (ChatGPT, Claude, Gemini, OpenCode, and future) can integrate with.
> **Hard constraint:** Must remain runnable at **$0/month** (existing free-tier mandate, see `docs/FREE_ARCHITECTURE.md`), while being *architecturally capable* of scale-out when commercial infra is justified.
> **Deliverable status:** D1–D9 = [01](./01-architecture.md)–[06](./06-sdk-design.md); D10/D18 = [07](./07-migration-plan.md); D11–D13 = [08](./08-reliability-ops.md); D14 = [09](./09-security-review.md); D15–D17 = [10](./10-roadmap.md).

---

## 1. Why v2

The v1 platform is request-based:

```
create_call → send_message → wait_for_reply(timeout=45) → return response
```

This design artificially limits conversations (45s cap), couples the AI to polling, cannot stream
speech, cannot barge-in, and cannot survive long interruptions. v2 replaces the request-response
core with a **persistent session + event stream** core. The single most important semantic change:

| v1 | v2 |
|----|----|
| `wait_for_reply(timeout=45)` blocking call | `subscribe_to_events()` + turn-state watcher — *no server-side cap* |
| AI polls `get_transcript` | server pushes `speechFinal`, `messageCompleted`, `TranscriptUpdated` |
| AI message is one atomic write | `MessageQueued → MessageStarted → MessageCompleted` with streaming TTS |
| user text is one atomic write | `SpeechStarted → SpeechPartial* → SpeechFinal` with streaming STT |
| hard-coded silence/timeout | configurable `silence.detected` events; *the AI decides* |
| interleaving impossible | barge-in (`userInterrupted`), turn-based conversation |
| single sign-in, in-process bus | pluggable event backbone (in-process / Redis Streams), stateless workers |

The user's requested example is the target developer experience:

```typescript
const call = await agentCall.create()
await call.say("Hello!")

call.on("speechFinal", async (event) => {
  await call.say(generateResponse(event.text))
})

call.on("interrupted", () => { /* TTS was cut, stop generation */ })
call.on("ended", () => console.log("Call finished"))
```

That is a native-mode SDK (Real-Time API style). The current MCP surface remains as a
**compatibility layer** and as a command-only transport for MCP-native clients
(OpenCode, Claude Code, Cline), so nothing that works today breaks.

---

## 2. Design principles

1. **The AI owns intelligence; AgentCall owns communication.** The platform does not embed an
   LLM. It exposes voice, events, transcript, and tool-invocation primitives to *any* AI.
2. **Sessions are first-class, long-lived objects.** A call is alive from creation until
   `Completed`/`Failed`/`Archived`. No timeouts occlude the state machine.
3. **Everything is an event; every transition emits an event.** State is derived from a
   persistent, replayable event log (event-sourcing-lite) — this is what makes crash recovery
   and horizontal scaling safe.
4. **Provider-agnostic media.** STT, TTS, and transport (WebRTC/SIP/browser/mobile) are behind
   small provider interfaces. On-device (free) providers are the default; cloud providers slot
   in without touching business logic. The **`$0/month` constraint is preserved** because free
   defaults are what ship.
5. **Idempotent commands, at-least-once events, dedupe consumers.** Reliability is designed in,
   not bolted on.
6. **Backward compatible.** `/api/v1`, the existing WS `/phone`, and the MCP tool names live on
   unchanged behind a compatibility façade.

---

## 3. Deliverable map

This directory contains all 18 requested deliverables.

| # | Deliverable | Document |
|---|-------------|----------|
| 1 | Complete architecture document | [01-architecture.md](./01-architecture.md) §1–§3 |
| 2 | Component diagram | [01-architecture.md](./01-architecture.md) §3 |
| 3 | Sequence diagrams | [02-sequence-diagrams.md](./02-sequence-diagrams.md) |
| 4 | State machine diagram | [01-architecture.md](./01-architecture.md) §5 |
| 5 | Event model | [03-event-model.md](./03-event-model.md) |
| 6 | API specification | [04-api-spec.md](./04-api-spec.md) §2 |
| 7 | Database schema | [05-database-schema.md](./05-database-schema.md) |
| 8 | WebSocket/SSE protocol | [04-api-spec.md](./04-api-spec.md) §3 |
| 9 | SDK design | [06-sdk-design.md](./06-sdk-design.md) |
| 10 | Migration plan v1→v2 | [07-migration-plan.md](./07-migration-plan.md) §2 |
| 11 | Performance targets | [08-reliability-ops.md](./08-reliability-ops.md) §2 |
| 12 | Failure recovery strategy | [08-reliability-ops.md](./08-reliability-ops.md) §3 |
| 13 | Testing strategy | [08-reliability-ops.md](./08-reliability-ops.md) §4 |
| 14 | Security review | [09-security-review.md](./09-security-review.md) |
| 15 | Implementation roadmap with milestones | [10-roadmap.md](./10-roadmap.md) §2 |
| 16 | Recommended technologies & libraries | [10-roadmap.md](./10-roadmap.md) §3 |
| 17 | Risks and trade-offs | [10-roadmap.md](./10-roadmap.md) §4 |
| 18 | Incremental migration / ship-value-early strategy | [07-migration-plan.md](./07-migration-plan.md) §3 |

---

## 4. The v2 architecture in six sentences

- A **CallService** persists a session per call and runs a **validated finite state machine**
  (`Idle → Creating → Ringing → Connecting → Connected → Paused → … → Completed/Archived`).
- A **MediaGateway** abstracts transports (WebRTC/SIP/browser/mobile) and routes audio through
  pluggable **STT** and **TTS** providers that produce streaming partial/final speech events.
- A **TurnCoordinator** owns the conversation rhythm: AI turns (queued → streaming TTS →
  completed), user turns (bursts → final), barge-in, and silence decisions.
- Domain events flow through an **EventPlane** (in-process bus today; Redis Streams / Kafka /
  NATS adapter interface for scale) and are durably recorded in an **event log** — the single
  source of truth for recovery, replay, and analytics.
- The **AI connects three ways**: MCP tools (commands only, backward-compatible), REST + SSE
  event streams (provider-agnostic), or the native **SDK** (WebSocket duplex event + media).
- Everything is **idempotent, authenticated, ownership-checked, audited, and observable** by
  default.

See [01-architecture.md](./01-architecture.md) for the full component and data-flow model.

---

## 5. Reading order

1. [01-architecture.md](./01-architecture.md) — the big picture (D1, D2, D4)
2. [03-event-model.md](./03-event-model.md) — the language of the system (D5)
3. [04-api-spec.md](./04-api-spec.md) — the contract (D6, D8)
4. [05-database-schema.md](./05-database-schema.md) — the durable truth (D7)
5. [06-sdk-design.md](./06-sdk-design.md) — what developers touch (D9)
6. [02-sequence-diagrams.md](./02-sequence-diagrams.md) — how it moves (D3)
7. [07-migration-plan.md](./07-migration-plan.md) — how we get there without breaking v1 (D10, D18)
8. [08-reliability-ops.md](./08-reliability-ops.md) — numbers, failure, tests (D11–D13)
9. [09-security-review.md](./09-security-review.md) — trust boundaries (D14)
10. [10-roadmap.md](./10-roadmap.md) — schedule, stack, risk callouts (D15–D17)

---

## 6. Glossary

| Term | Meaning |
|------|---------|
| Call / Session | Long-lived conversational aggregate. Unique `call_id`. |
| Turn | One exchange unit. AI-turn: queued→started→completed. User-turn: speech burst → final. |
| Event | Versioned, immutable fact about a session (`call.connected`, `speech.final`, …). |
| Event Plane | The transport that moves events (in-process bus, Redis Streams, NATS adapter). |
| Event Log | Durable, replayable record of every event (Postgres table). |
| Media Gateway | Ownership layer over transport + STT + TTS provider instances. |
| Interaction / Utterance | One user speech burst, from `speech.started` to `speech.final`. |
| Barge-in | User starts speaking while TTS streams; TTS stops, `user.interrupted` emitted. |
| Turn Lease | Server-side marker that the AI's turn is in flight (replaces the 45s wait). |
| Provider | Pluggable STT / TTS / transport implementation behind a fixed interface. |
| Compatibility façade | v1 REST + `/phone` WS + MCP tool names, mapping onto v2 internals. |

---

## 7. Key decisions at a glance (ADRs summarized)

| Decision | Choice | Why |
|----------|--------|-----|
| Event backbone | In-process bus now; Redis Streams behind a `StreamAdapter` interface | $0 today, scale path later without design change |
| Source of truth | Event log (Postgres) + derived session view | Crash recovery & replay without dual-maintenance |
| AI protocol | MCP (commands) + SSE/WS (events) + SDK (all-in-one) | Every client class gets a first-class path |
| STT/TTS | Provider interface, on-device default | $0 constraint + vendor flexibility |
| Wait-for-reply | Turn lease + event subscription, **no server cap** | Removes the fundamental v1 limit |
| Transport | `TransportProvider` interface (WebRTC primary) | Swappable without business-logic changes |
| Scale | Stateless workers + call id routing + Redis | Thousands of concurrent calls with graceful failover |

Each decision is elaborated with alternatives and trade-offs in [10-roadmap.md](./10-roadmap.md) §4.