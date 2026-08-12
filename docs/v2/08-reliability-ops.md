# 08 — Reliability, Performance Targets & Testing Strategy

> **Deliverables:** 11 (performance targets), 12 (failure recovery strategy), 13 (testing strategy)
> **Companion docs:** [01-architecture.md](./01-architecture.md) §6–§7, [05-database-schema.md](./05-database-schema.md) §3

---

## §1. SLIs / SLOs (what "reliable" means)

| SLI | SLO (target) | Notes |
|-----|--------------|-------|
| Event delivery (AI-facing) | ≥ 99.9% of events delivered ≤ 1s after commit | at-least-once; dedupe by `event_id` |
| Call availabilty | ≥ 99.5% of calls reach `connected` and stay connected | excludes human hangup |
| Media continuity | ≥ 99.0% of connected minutes without a > 2s audio gap | jitter/backoff budgets |
| Crash recovery | RTO < 5s, RPO = 0 | event-log replay (§3.1) |
| Barge-in cut | p95 ≤ 50 ms from VAD fire to TTS stop | hard platform-side guarantee |
| Event surface | ≤ 1 in 10⁶ delivered events is out-of-order for a call | per-call sequence strictly |
| Publish durability | 0 events lost on publisher failure | outbox-first write (§ [05] §3) |

---

## §2. Performance targets (D11)

Budgets are **end-to-end on-device → platform → on-device** unless stated; measured on a
single Hetzner-class node (2 vCPU / 4 GB) with an on-device provider for the $0 tier.

| Metric | Target (p95) | Budget notes |
|--------|-------------|--------------|
| Call create → `call.created` visible to AI | < 150 ms | FSM + outbox insert |
| Ring → answered → `call.connected` | < 1.5 s | incl. media negotiation offer/answer |
| STT first partial (`speech.partial`) | < 800 ms | VAD fire → event |
| STT final (`speech.final`) | < 1.2 s after utterance end | on-device default; cloud varies |
| TTS first byte (`message.started`, TTFB) | < 500 ms | streaming tokens → first audio |
| Full turn e2e (speech.final → message.completed) | < 3 s | realistic LLM+tool turn |
| Barge-in hard stop | ≤ 50 ms | TTS `.stop()`; see §1 |
| Silence detection emission | ≤ 100 ms after threshold | per-call timer |
| Event publish → subscriber-received (in-process) | < 1 ms | bus; ~1–5 ms Redis |
| Subscriber fan-out / connect | support ≤ 1,000 concurrent SSE/WS consumers per node | per-call supersession enforced |
| Single node call capacity | ≥ 2,000 concurrent calls | @ $0 default config; stateless workers scale out (§ scalability in 01-architecture §3.2) |
| Event log insert | ≥ 2k evt/s/node sustained | single tx (event + projection) |
| Transcript read | GET returns last 200 segments < 50 ms | index `(call_id, seq)` |

**Provider caveat:** real STT/TTS vendor latencies are concrete and variable; the platform
normalizes them via provider adapters, and the latency.metric events record the breakdown
(`stt.partial`, `stt.final`, `tts.ttfb`, `turn.e2e`) so dashboards track reality, not the
budget above.

---

## §3. Failure recovery strategy (D12)

### 3.1 Process crash (worker dies, no graceful shutdown)

1. Event log (Postgres, outboxed) is the durable truth — nothing in-flight is lost (RPO 0).
2. On startup, `RecoveryManager` (v1 pattern, extended) replays events per orphaned call,
   rehydrates session state and FSM, rebuilds pending timers (silence, ring, callback) from
   the last event timestamps, and marks media dead.
3. The replacement worker emits `call.reconnecting` (+`media.reconnecting`); device SDK
   auto-reconnects with backoff (3s→6s→15s cap) and resumes via `Last-Event-ID`.
4. Target: **human hears ≤ 5s audio gap; conversation state intact.**

Replay is **idempotent** — state transitions from events, and re-applying the same events is a
no-op for the derived projection (event-sourcing-lite; see $ guarantees in §3.4).

### 3.2 Network partition / media drop

- Heartbeat: RTCP + app ping on the media channel (25s server ping; 20s client ping — v1
  pattern retained); two missed beats ⇒ declare `media.disconnected`, enter `reconnecting`.
- Auto-reconnect with jittered backoff, media renegotiation, and a drained jitter buffer.
- `quality.metric` (throttled 1/s/call) feeds the dashboard and triggers ops alerts on
  persistent packet loss.

### 3.3 Database outage

- Because the process is stateless-only-at-edge and *the event log is the outbox*, an outage is
  fail-fast: commands wait on the pool (configured acquire timeout, existing v1 `db-health-monitor`),
  health endpoint returns 503, connection pool recovers. No events are silently discarded —
  in-flight commands surface `DB_UNAVAILABLE` to the AI, which may retry (idempotency makes
  the retry safe).
- **Dual-write proviso:** during the transition window only (Phase 3 of migration), if a
  secondary path is unavailable the compatibility façade logs and continues on the primary
  path; the cliient contract is unchanged.

### 3.4 Duplicate events, poison events, and ordering

| Threat | Mechanism |
|--------|-----------|
| Duplicate event delivery (at-least-once) | consumers dedupe by `event_id` (`SubscriptionManager` cursor + seen-set); re-publish after replay is a no-op |
| Publisher lost events | outbox discipline ([05-database-schema.md](./05-database-schema.md) §3): insert event + projection in one tx, publish *after commit*; `EventRelayer` sweeps unpublished events and re-publishes |
| Reordered events | per-call monotonic `sequence` in the log; consumers detect gaps and resync (`stream.resync`); total per-call order in the log |
| Poison event (payload invalid in prod) | validated at emit (dev: throw; prod: log & continue, enqueue to dead-letter queue with original envelope) |
| Duplicate command (retry) | `Idempotency-Key` / `client_message_id` → stored first response replayed (`X-Idempotent-Replay: true`) |

### 3.5 Consumer backpressure (SSE/WS)

Never lean on unbounded buffers. A slow consumer triggers:
1. write pause (REST-level pauses),
2. if a hard watermark is hit, `stream.resync {last_persisted_id}` forces the client to
   reconnect and replay from that id — **drops are never silent** ([04-api-spec.md](./04-api-spec.md) §3.1).

### 3.6 Chaos-driven verification

Recovery properties are exercised with fault injection (see §4.4), matching the repo's existing
chaos/verification report practices (e.g. `docs/reports/CHAOS_TEST_REPORT.md`).

---

## §4. Testing strategy (D13)

### 4.1 Pyramid and tools

| Tier | Tool | Scope |
|------|------|-------|
| Unit (FSM, providers, validation) | Vitest (existing) | pure FSM transitions & guards; zod schemas; decomposition function |
| Integration (Postgres-backed) | Vitest + live driver/agent harnesses (repo pattern) | outbox transaction, replay, backfill verifier, cursors |
| Contract (v1 compat) | Vitest, pinned goldens | every v1 REST/WS/MCP behavior asserted before each migration phase ([07-migration-plan.md](./07-migration-plan.md) §2) |
| E2E | Playwright (browser WebRTC) + on-device simulators | full conversation: ring→speech→barge-in→transcript→end |
| Load / chaos | k6 + fault injection | scalability budgets (§2), §3 fault-injection properties |

### 4.2 FSM verification

- **Property-based exhaustive transition tests:** a table carrying [from, command, guard,
  payload, expected to] for every row of the transition table in [01-architecture.md](./01-architecture.md)
  §5.3 — the table in the doc *is* the test data.
- **Invalid-transition tests:** every illegal edge returns `INVALID_TRANSITION` (409) and
  emits nothing.
- Concurrency: two racing commands on the same call → optimistic `fsm_version` protects; one
  wins, the loser gets `SESSION_LOCKED` (423, brief).

### 4.3 Real-time determinism

Streaming behavior is tested deterministically with **fake providers** (scripted STT partials,
scripted TTS token timing) so latency/barge-in/reconnect assertions aren't flaky. Golden
transcript tests replay a recorded event log and assert the exact derived projection.

### 4.4 Fault-injection suite (chaos)

| Fault | Assertion |
|-------|-----------|
| Kill worker mid-`speaking` | replay rehydrates FSM=connected; `media.reconnecting`; RTO < 5s, RPO 0 |
| Drop media channel mid-call | `media.disconnected` → auto-reconnect → `media.reconnected`; ≤ 2s audio gap |
| Duplicate publish of same event | consumer de-dupes; transcript not duplicated |
| DB unreachable during a command + retry | idempotency key ⇒ single logical write; no partial state |
| Slow/poison event payload | DLQ without crash; other calls unaffected |

### 4.5 Continuous quality gates

`npm run lint` (+ tsc `--noEmit`) and `npm test` on every commit; contract suite is the
pre-merge requires gate for any change touching the façade or FSM. Coverage floor: 80% across
the v2 engine modules (check-coverage in CI), 100% on the FSM table.

---

## §5. Operational runbooks (summary)

See [09-security-review.md](./09-security-review.md) §7 for the security runbook; key ops
runbooks here:

| Incident | First action | Owner |
|----------|--------------|-------|
| Elevated drop/failed calls | pull per-provider STT/TTS latency.metric breakdown | Media Gateway owner |
| Event delivery > 1s P99 | check EventRelayer backlog + consumer cursors | EventPlane owner |
| Call stuck in non-terminal state > N min | replay event log; inspect last event; force `ending→completed` via hangup command | Recovery owner |
| Reconnect storm | global connection rate-limit; per-call backoff; check coturn/STUN | Transport owner |