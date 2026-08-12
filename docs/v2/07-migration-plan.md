# 07 — Migration Plan: v1 → v2

> **Deliverables:** 10 (migration plan v1→v2), 18 (incremental migration / ship-value-early)
> **Companion docs:** [README.md](./README.md) §4, [01-architecture.md](./01-architecture.md) §8,
> [04-api-spec.md](./04-api-spec.md) §1, [05-database-schema.md](./05-database-schema.md) §3–§4

---

## 1. Migration goals and constraints

| # | Constraint | Behavior |
|---|------------|----------|
| C1 | **Zero breakage** | Every v1 surface (REST `/api/v1`, WS `/phone`, MCP tools) behaves exactly as today for the entire migration. No v1 endpoint is removed or relabeled. |
| C2 | **Dual-run, not fork** | v1 and v2 code paths run side-by-side inside the same process from day one. There is never a big-bang cutover. |
| C3 | **Live rollback** | Every phase has a flip-back that takes ≤ one restart minutes, and a data-safe story (nothing destructive sequentially). |
| C4 | **New-tables-in-parallel** | v2 tables are created alongside v1 tables. v1 tables are **never** dropped or altered destructively during the migration window (see [05-database-schema.md](./05-database-schema.md) §4). |
| C5 | **Versioned compatibility** | The compatibility façade is a seam, not a patch: every v1 contract is *tested* against the v2 engine, not assumed. |
| C6 | **$0 default preserved** | Default configuration stays in-process (single process, in-process bus, dual-write off). Nothing in the migration *requires* Redis/cloud infra. |

---

## 2. The compatibility façade (what must keep working)

### 2.1 Inventoried v1 surface (source of truth: current `backend/src/routes.ts`, `…/mcp/tools.ts`, `…/voicebridge/service.ts`)

| Layer | Surviving surface | v2 mapping |
|-------|-------------------|------------|
| REST | `POST /api/v1/calls` | `createCall` command |
| REST | `GET /api/v1/calls/:callId` | session snapshot query |
| REST | `POST /api/v1/calls/:callId/messages` | `sendMessage` (non-blocking) |
| REST | `POST /api/v1/calls/:callId/user-text` | `submitUtterance` (idempotent via `client_message_id`) |
| REST | `GET /api/v1/calls/:callId/transcript` | transcript query |
| REST | `GET /api/v1/calls/:callId/pending-reply` | turn-lease status snack (same shape) |
| REST | `POST /api/v1/calls/:callId/complete` [`cancel`] | `hangupCall` with `outcome.decision` (complete/cancelled) |
| REST | `POST /api/v1/calls/:callId/answer` | `answerCall` |
| REST | `GET /api/v1/users/:userId/active-call` | active-call query |
| REST | `POST /api/v1/calls/:callId/callback` | `pauseCall` mapping (schedule re-ring) |
| REST | `POST /api/v1/phone/token` · `…/phone/register` | unchanged (device identity) |
| REST | `POST/GET/DELETE /api/v1/ai/keys` | unchanged, extended columns in v2 schema |
| HTTP | `/api/v1/health` · `/ready` · `/metrics` | unchanged contracts |
| WS | `/phone` messages: `call_incoming`, `call_answered`, `ai_message`, `callback_scheduled`, `ai_wait_status`, `call_ended` | device projections of v2 events (see [03-event-model.md](./03-event-model.md) §6) |
| MCP | `create_call`, `send_message`, `send_message_and_wait`, `get_transcript`, `complete_call`, `cancel_call` | v2 tools under the same names; `send_message_and_wait` becomes lease + event-sub (no server cap) |

### 2.2 What counts as "not broken"

A conformance **contract test suite** (`vitest` + live drivers, mirroring the repo's existing
`__tests__` drivers) pins every row above *before* the internal engine changes. The suite runs
in CI on every commit and is the release gate for each phase below. V1 behavior is defined as
"what these tests assert today" — any intended improvement (e.g. `send_message_and_wait`
no longer returning `timeout` after 45s) is a *documented, opt-in* behavioral change gated by a
feature flag, **not** a silent difference.

### 2.3 Façade implementation shape

```
v1 HTTP route / WS frame / MCP tool
        │
        ▼
Compatibility façade  (owns: v1 shapes, v1 error format, v1 rate limits, v1 field names)
        │  calls v2 services with v1-shaped args
        ▼
v2 CommandBus + CallService (FSM, idempotency, events)
        │
        ▼
Adapt v2 result/errors back to v1 shape: { ... } / { error, message } / MCP ToolResult
```

The façade is thin: it does **not** re-implement business logic; it translates contracts. The
existing v1 `VoiceBridgeService` is refactored *from the inside* so its methods delegate to v2
commands instead of the reverse (invert the dependency, keep the signature).

---

## 3. Migration phases (each ships independently)

Each phase is independently releasable, deployable, and reversible. Milestone names, exit
criteria, and staffing notes live in [10-roadmap.md](./10-roadmap.md) §2; this section is the
*mechanics*.

### Phase 0 — Freeze & baseline (no production change)

- Pin the v1 behavior contract suite (2.2). Tag **v2.0-alpha**.
- Inventory v1 `sessions.data` JSONB shapes (messages array, result, context, timestamps) that
  backfill must decompose.
- Add v2 tables to the migrations directory (empty, additive, `IF NOT EXISTS`) — deployed but
  unused. Verify zero impact on v1 write paths.

**Exit:** contract suite green on `main`; migration script is additive-only (code-reviewed,
see `db-migration-safety`).

### Phase 1 — v2 core beside v1 (new engine, no routing change)

- Land `CallService` + FSM + `EventPlane` (in-process) behind **new** `v2` namespaces:
  `POST /api/v2/calls`, `…/messages`, `…/utterances`, `…/events` (SSE/WS). No v1 route touches
  these.
- Land `createCall`/`sendMessage`/`submitUtterance`/`hangupCall` with idempotency, ownership,
  audit, and the durable outbox event write.
- `NOT_FOUND` on v2 for stale call ids — v2 tracks its own calls only for now.
- `send_message_and_wait` (MCP) and `complete_call`/`cancel_call` are re-pointed at the façade,
  which now routes through v2 `hangupCall` while preserving the v1 return contract.

**Ship value:** MCP clients get the new lease semantics and the v2 event URLs *today*, while
every existing test still passes. Rollback = revert the façade pointer (one-line flag).

### Phase 2 — Backfill & dual-read

- Backfill job decomposes `v1.sessions.data` → `calls` + `transcript_segments` (+ `turn_leases`,
  `tool_invocations` on demand). Idempotent; restartable; verifies counts on every run
  (mirrors `repositories/verifier.ts`).
- `GET /api/v1/calls/:callId`, `transcript`, `pending-reply`, `active-call` switch to
  **dual-read**: read v2 projection; if missing, fall back to v1; assert on divergence.
- Failover posture: a read path that diverges logs an alert, serves v1 data, and never
  errors the client.

**Ship value:** operators see v2 projections populated for all historical calls without any
client change.

### Phase 3 — Write switchover (`PERSISTENCE_MODE=v2`)

- New calls persist only to v2 tables. v1 write paths are shut off behind the flag
  (they become read-only compatibility views for the retention window).
- The WS `/phone` projections and MCP notify path are re-derived from the v2 event log
  (event-sourcing projection), replacing `dual-write` as the only production mode
  (memory-only remains dev/test — parity with today's `memory` mode).
- Retention sweepers move to `calls.retention_expires_at` semantics.

**Exit:** 100% of new calls are v2-native; v1 contract suite still green (served by the
façade). Flip-back = `PERSISTENCE_MODE=dual-write` restart (safe because v1 tables were never
dropped).

### Phase 4 — Decommission & deprecate

- Delete v1-only code paths *except* the façade, which remains as the documented compatibility
  layer for old SDKs/clients (v1 endpoints keep working indefinitely — they just sit on v2).
- `sessions.data` decomposition is considered complete; v1 tables get retention/archival per
  policy, not deletion.
- Events bump on `send_message_and_wait` no-cap behavior becomes default after a deprecation
  notice (see §4).

**Exit:** codebase has exactly one conversation engine; the façade is the only v1-shaped code
left.

---

## 4. Deprecation policy (referenced from [03-event-model.md](./03-event-model.md) §5)

- **Additive first:** new event types/payloads are added under new dotted names
  (`speech.final.v2`) during transition; the old type is emitted in parallel until no consumer
  advertises the old one.
- **Notices:** a behavior change ships with a ≥ 90-day notice in CHANGELOG + the API spec
  `Deprecation:` marker; the old path keeps a compatibility emission for `2 major` releases.
- **Tool retirement:** legacy MCP tool names (`complete_call`, `cancel_call`) are *never*
  deleted — they are permanently aliased at the façade. Reduced-risk removal targets only
  internal-only events (`usage.metric`, `audit.event`).

---

## 5. Data migration detail (sessions.data decomposition)

v1 keeps a single `sessions` row per call with a JSONB `data` blob. The backfill maps:

| v1 `data` field | v2 target |
|-----------------|-----------|
| `messages[]` (turn pairs) | `transcript_segments` rows (role `user`/`ai`; `start_ms`/`end_ms` = call-relative at finalize; `seq` = transcript_seq) |
| `result` (outcome) | `calls.result` |
| `context` (task, options, custom) | `calls.context` |
| `status`/timestamps | `calls.status/phase` + `created_at/ringing_at/connected_at/ended_at` |
| `callbacks` rows | `calls.paused_at` + `callbacks` (kept, legacy read) |

The backfill is a **pure function** (`decomposeSession(row) → events[]`) — identical to the
write path used for live calls, so the projection builder is tested once and reused. It runs
in batches with a cursor and a verifier that compares (per region: call counts, segment
counts, aggregated text hash) on every completed batch.

---

## 6. Rollback plan

| Trigger | Action | RTO | Data risk |
|---------|--------|-----|-----------|
| Any v1 contract test regresses | `rg -i switchover` env: revert façade pointer to `dual-write`; restart | minutes | none (v1 tables intact) |
| v2 projection divergence at dual-read | serve v1, alert; fix projection | minutes | none |
| FSM bug affecting live calls in v2 | flip `PERSISTENCE_MODE=dual-write`; drain v2-only calls per policy | minutes | completed v2 calls retained (v2 tables keep them); no loss |
| Redis/stream adapter failure | fall back to `InProcessBus` single-instance mode | within 1 minute (config + restart) | events already outboxed; replay on return |

Because the event log is the single source of truth and commands are idempotent, rollback
never leaves half-written state: replayed events rehydrate anything incomplete.

---

## 7. Ship-value-early: the incremental wins per phase

The redesign is deliberately vertical-rewrite-avoidant. Each of these ships *without* the
full v2 platform:

| Ship when | Value | Minimal v2 surface needed |
|-----------|-------|---------------------------|
| Phase 1a | **SSE event stream** for new calls — AI stops polling; `speech.final`/`message.completed`/`turn.ended` push | `POST /v2/calls`, event log write, SSE dispatcher |
| Phase 1b | **Turn lease** (no 45s cap) for `send_message_and_wait` | lease + `turn.ended` event |
| Phase 1c | **Silence events** (`silence.detected`, `call.noactivity`) replace the fixed timeout | silence policy timer in TurnCoordinator |
| Phase 1d | **Streaming partials** (`speech.partial`) and barge-in on device | VAD + STT partial emission + `stop` on TTS |
| Phase 2 | **Live, searchable transcript** endpoint with `after=`/`partials` | transcript projection |
| Phase 3 | **Stateless workers + Redis-backed events** for horizontal scale | StreamAdapter impl |
| Phase 4 | STS/OPA-grade audit + retention automation | audit log + retention sweep |

Every row is independently deployable, testable against the frozen v1 contract suite, and
reversible. The **45s ceiling is lifted in Phase 1** because it is purely a server semantics
change (lease), not an architectural one.