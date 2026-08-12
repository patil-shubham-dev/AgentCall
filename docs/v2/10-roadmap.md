# 10 — Roadmap, Technology Stack & Risk Register

> **Deliverables:** 15 (implementation roadmap with milestones), 16 (recommended technologies &
> libraries), 17 (risks and trade-offs)
> **Companion docs:** [07-migration-plan.md](./07-migration-plan.md) (phases), [08-reliability-ops.md](./08-reliability-ops.md) (targets), [09-security-review.md](./09-security-review.md)

---

## 1. Guiding commitments

1. **Ship value at every milestone; never a big-bang rewrite** — each milestone is independently
   deployable and reversible ([07-migration-plan.md](./07-migration-plan.md) §3).
2. **The $0 constraint stands** — default config runs single-process, in-process event bus,
   on-device STT/TTS; cloud/providers/Redis are optional upgrades, never prerequisites.
3. **Every milestone ends green** on the v1 contract suite + unit/integration/coverage gates.

---

## 2. Implementation roadmap (D15)

Timelines are relative estimates for a 2–4 engineer squad with the existing codebase knowledge;
adjust to team reality. Milestones map 1:1 onto migration phases ([07] §3).

| Milestone | Deliverables | Exit criteria | Duration |
|-----------|--------------|---------------|----------|
| **M0 — Baseline freeze** | v1 contract suite (REST/WS/MCP goldens); v2 tables added (additive); ADRs for the 8 key decisions (README §7) | suite green on `main`; additive-only migrations merged | 1–2 wk |
| **M1 — v2 engine core** | `CallService` + FSM + `EventPlane` (in-process) + outbox write path; idempotency keys; `POST /api/v2/*` for create/message/utterance/hangup; SSE `events` endpoint; MCP `send_message_and_wait` → lease semantics | v1 suite green; v2 contract tests green; 45s cap gone behind flag | 3–5 wk |
| **M2 — Realtime conversation** | Streaming STT partials + final; streaming TTS (token→audio); barge-in ≤ 50 ms; silence events (`silence.detected`, `call.noactivity`); turn leases + `turn.ended` | scripted-provider tests deterministic; barge-in p95 ≤ 50 ms measured | 4–6 wk |
| **M3 — Durability & recovery** | Event-log replay (RecoveryManager v2); timer reconstruction; backfill + verifier; dual-read of transcript/pending-reply; `PERSISTENCE_MODE=v2` | chaos suite green: worker-kill RTO < 5 s, RPO 0 | 3–4 wk |
| **M4 — Tools & devices** | `ToolInvoker` + `invoke_tool` MCP tool; scope-filtered subscriptions (device never sees tool args); media channel (WS `v2/media`) + WebRTC/coturn attach; `pause/resume/transfer` | tool call invisible to device; WebRTC E2E call in Playwright | 4–6 wk |
| **M5 — Scale-out (optional tier)** | `StreamAdapter` → Redis Streams (Valkey); stateless workers + sticky routing; consumer cursors/replay; supersession | 2,000 concurrent calls on 2 nodes; event latency ≤ 5 ms P95 | 4–6 wk |
| **M6 — SDK & observability** | `@agentcall/sdk` 1.0 (managed+ native), Python SDK; Grafana dashboards (per-state gauges, STT/TTS provider breakdowns); usage_daily + audit tooling | SDK E2E against staging; dashboards answering ops questions | 3–4 wk |

**Sequencing note:** M1–M4 are the critical path for "no 45s cap + real-time"; M5 is opt-in
(no commercial infra required to ship). M6 can start in parallel from M2.

---

## 3. Recommended technologies & libraries (D16)

### 3.1 Core (keep — proven in v1, no churn)

| Area | Choice | Why |
|------|--------|-----|
| HTTP/API | Fastify 4 (current) | plugin ecosystem, hooks, schema speed; keep |
| Validation | Zod (current) | project rule; typed at every boundary |
| WebSocket | `ws` (current) | battle-tested; media channel on same lib |
| DB | PostgreSQL 16 + `pg` (current) | event log truth; JSONB; GIN indexes |
| Logging | pino + pino-pretty (current) | structured, low overhead, redact support |
| Tests | Vitest + existing live-driver harnesses | already the repo's pattern |
| Reverse proxy/TLS | Caddy (infra, current) | auto TLS, SSE-friendly `X-Accel-Buffering` |

### 3.2 Additions (verified needs — no new packages without a stated reason)

| Area | Recommendation | Notes |
|------|----------------|-------|
| Event transport (scale tier) | **Valkey/Redis Streams** via a thin `StreamAdapter` (no client yet — write adapter interface first, add `ioredis` only when M5 starts) | $0 self-hosted; XREADGROUP consumer groups; replaces in-process bus at scale |
| Provider SDKs (STT/TTS) | `deepgram`, `elevenlabs`, OpenAI `realtime` etc. as *optional* adapter packages (`@agentcall/…-provider-*`), never core deps | keeps core $0; vendor swap is config |
| Media (WebRTC) | native `wrtc`/`mediasoup` or LiveKit *behind* `TransportProvider`; coturn stays for TURN | do not bind core to one engine |
| Time | no new lib — `setTimeout` scheduler + existing `cleanup-scheduler` pattern, rebuilt from event log on recovery | timers are policy, not truth |
| Metrics | Prometheus client + Grafana (v1 already exports /metrics) | dashboards per §2 M6 |
| CI/CD | existing GitHub Actions + Render/Hetzner deploy; add contract-suite + chaos jobs | |
| SDK | TypeScript `@agentcall/sdk` (no deps beyond `ws`), Python later (aiohttp + sseclient) | versioned per [06-sdk-design.md](./06-sdk-design.md) §11 |

### 3.3 Explicitly rejected

| Library | Why rejected |
|---------|--------------|
| BullMQ/agenda for timers | timers must survive crash via event log; a message-queue as timer-owner adds a second source of truth |
| Mongoose/Prisma for schema | Knex/pg DDL + zod gives full control; avoid ORM abstraction over event-sourcing |
| Socket.io | heavier, transport-coupled; `ws` + SSE covers both directions |

---

## 4. Risks & trade-offs (D17)

| # | Risk / trade-off | Mitigation | Owner |
|---|------------------|------------|-------|
| R1 | **Event-sourcing-lite complexity** (projection drift, replay bugs) | derived projections rebuilt from the log; verifier job compares counts/hashes; chaos replay tests | Recovery owner |
| R2 | **Streaming STT/TTS vendor latency & cost at scale** | provider abstraction isolates; on-device default keeps $0; latency.metric breakdowns surface regressions | Media Gateway owner |
| R3 | **Barge-in semantics on devices without local VAD** (network latency between VAD and cut) | platform-side VAD + hard server cut; device `stop` control; barge-in ≤ 50 ms budget in the media channel spec | Transport owner |
| R4 | **Prompt/audio injection via speech or tool output reaching the AI's prompt** | actor-tagging, scope filters, documented AI-side guidance; never echo tool output to devices | Security owner |
| R5 | **Backward-compat drag** — façade keeps v1 shapes forever | façade is thin contract translation; only retired internally; no engine duplication | Migration owner |
| R6 | **Distributed-event ordering under Redis** (per-call total order required) | per-call `sequence` in the log as the arbiter; consumer cursors + `stream.resync` on gaps | EventPlane owner |
| R7 | **"No timeouts" ergonomics regression** — AI clients written for polling may wait forever on silence | `silence.detected` + `call.noactivity` escalation events; SDK `waitForReply` resolves on turn.ended; docs examples | SDK owner |
| R8 | **Single-node perf ceiling at $0 tier** | M5 scale tier is opt-in and pluggable; load budgets (§2 of [08]) validated per node | Ops owner |
| R9 | **Recordings / retention legal exposure** (speech data) | retention default 30 d, configurable per call; encrypted at rest; audit of deletion; right-to-erasure endpoints | Compliance owner |
| R10 | **Mobile on-device STT/TTS quality gap vs cloud** | provider selection is per-call config; quality.metric + transcript quality score surface the gap; A/B via provider switch | Product owner |

### Trade-off callouts (decisions made, with rationale)

1. **Event log as truth vs. plain rows** — pays for crash recovery/RPO 0/horizontal replay; costs
   projection maintenance. Accepted: v1 already writes events; this formalizes them.
2. **In-process bus now, Redis later** — chooses $0 today over premature distribution; the
   adapter seam (not the bus) is the architecture.
3. **On-device STT/TTS default** — chooses free tier & privacy over best-in-class accuracy;
   correctness path via pluggable providers.
4. **Façade forever vs. sunset** — chooses zero customer migration cost over cleanup; the
   façade is thin by construction, so long-term cost is small.

---

## 5. Definition of done for v2 GA

- All 18 deliverables in this document set implemented per spec; v1 contract suite green on
  `main`; M1–M4 merged; chaos + load gates passing; SDK 1.0 published; dashboards live;
  security checklist (09 §12) signed off; deprecation notices issued for behavior changes.