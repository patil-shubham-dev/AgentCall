# v2 Call Engine Readiness Audit — 2026-09-09

**Scope:** Report only, no code changes. Every claim cites the working tree at this commit (`cf44131` HEAD scope, v1 audit `docs/AUDIT_MCP_CALL_FLOW_2026-09-09.md` as the v1 capability baseline). Files read in full this session: `backend/src/v2/call-service.ts` (1069 lines), `backend/src/v2/routes.ts` (488 lines), `backend/src/v2/events.ts`, `backend/src/v2/call-fsm.ts`, `backend/src/v2/event-plane.ts`, `backend/src/v2/event-log.ts`, `backend/src/v2/recovery.ts`, `backend/src/v2/idempotency.ts`, `backend/src/v2/providers.ts`, `backend/src/v2/errors.ts`, `backend/src/v2/ids.ts`, `backend/src/v2/db/schema.ts`, `backend/src/v2/db/pg-event-log.ts`, `backend/src/v2/db/pg-idempotency.ts`, plus `backend/src/mcp/tools.ts:230-364`, `backend/src/mcp/endpoint.ts:129-188`, `backend/src/index.ts:360-476`, `backend/src/scripts/db-up.ts`, `backend/src/__tests__/helpers/v2-pg.ts`, and grep-verified line numbers in `backend/src/voicebridge/service.ts`, `backend/src/routes.ts`, `render.yaml`, `.env.example`, `backend/.env.example`, `infra/k8s/03-configmap.yaml`, `.github/workflows/`, and `mobile/android/app/src/main/java/com/agentcall/app/data/api/ApiService.kt`.

---

## 1. FEATURE PARITY VS V1

v1 capability baseline is `docs/AUDIT_MCP_CALL_FLOW_2026-09-09.md` §§1–4: six MCP tools (`create_call`, `send_message`, `send_message_and_wait`, `get_transcript`, `complete_call`, `cancel_call`), REST surface in `backend/src/routes.ts` (line numbers verified this session, §1.8), ring dispatch/FCM, sweeps/staleness, idempotency.

### 1.1 Capability-by-capability parity table

| v1 capability (v1 location) | v2 equivalent | Status |
|---|---|---|
| `create_call` MCP tool → `VoiceBridgeService.createCall` (`backend/src/voicebridge/service.ts:462`, via `backend/src/mcp/tools.ts:56-110` per prior audit) | `POST /api/v2/calls` → `V2CallService.createCall` (`backend/src/v2/routes.ts:189-222`, `backend/src/v2/call-service.ts:219-273`). Emits `call.created` + `call.ringing`, lands in `ringing` state. Ownership enforced: AI identity may only create calls it owns (`backend/src/v2/call-service.ts:224-226`). | **Equivalent, different shape.** v2 returns `{ call_id, status, events_url, created_at }` (`backend/src/v2/routes.ts:212-220`) vs v1's `{ call_id, status, instruction }`. No `task_id`/`options` top-level parity gap: v2 accepts `context.{task_id, summary, options, custom}` (`backend/src/v2/routes.ts:47-54`). |
| `send_message` MCP tool → `addAiMessage` (`backend/src/voicebridge/service.ts:618`, via `backend/src/mcp/tools.ts`) | `POST /api/v2/calls/:callId/messages` → `V2CallService.sendMessage` (`backend/src/v2/routes.ts:286-294`, `backend/src/v2/call-service.ts:313-344`). Emits `message.queued → message.started → message.completed → transcript.updated → turn.ended → turn.lease` and **blocks until the whole turn is durably recorded** (`backend/src/v2/call-service.ts:330-336`). | **Equivalent, different semantics.** v1 returns immediately after persist+push; v2 `sendMessage` awaits TTS stream completion (`stream.finished` + `Promise.all(stream.emits)`, `backend/src/v2/call-service.ts:334-336`). Non-blocking variant exists separately: `POST .../speak` → `V2CallService.speak` returns 201 after queue, streams in background (`backend/src/v2/routes.ts:301-309`, `backend/src/v2/call-service.ts:351-380`). |
| `send_message_and_wait` (blocking wait with reply/timeout/noactivity outcomes, `backend/src/mcp/tools.ts:261-361`) | **No equivalent.** No v2 route blocks waiting for a human reply. The v2 read paths are `GET .../transcript` (poll, `backend/src/v2/routes.ts:377-393`) and `GET .../events` SSE subscribe with `?after=`/`Last-Event-ID` resume (`backend/src/v2/routes.ts:400-475`). The v2 `turn.lease` event (`active_until: null`, `backend/src/v2/call-service.ts:1046-1054`) is the *data* a waiter would consume, but no waiter consumes it server-side. | **Missing.** The MCP tool surface has no v2-backed wait (see §1.9). |
| `get_transcript` MCP tool → `getTranscript` (`backend/src/voicebridge/service.ts:872`) | `GET /api/v2/calls/:callId/transcript` → `V2CallService.getTranscript` (`backend/src/v2/routes.ts:377-393`, `backend/src/v2/call-service.ts:678-707`). Supports `?after=<seq>`, `?limit=` (clamped 1–500, `backend/src/v2/routes.ts:382`), `?partials=true` for live partial append (`backend/src/v2/call-service.ts:688-702`). | **Equivalent, superset.** v2 segments carry `seq`, `role`, `type`, `text`, `start_ms/end_ms/confidence`, `is_partial` (`backend/src/v2/call-service.ts:21-32`) vs v1's `{ id, role, content, created_at }`. |
| `complete_call` MCP tool → `completeCall` (`backend/src/voicebridge/service.ts:673`) + `POST /api/v1/calls/:callId/complete` (`backend/src/routes.ts:457`) | `POST /api/v2/calls/:callId/hangup` → `V2CallService.hangupCall` (`backend/src/v2/routes.ts:276-284`, `backend/src/v2/call-service.ts:601-647`). Idempotent terminal no-op on re-hangup (`backend/src/v2/call-service.ts:606`). Hangup note becomes a user transcript segment (`backend/src/v2/call-service.ts:614-621`). Result shape differs: `{ outcome, note }` (`backend/src/v2/call-service.ts:75`) vs v1's `{ transcriptSummary, userResponse, decision, selectedOption, sentiment, actionItems }` (`backend/src/voicebridge/types.ts` per prior audit). | **Equivalent, different result schema.** No `sentiment`/`action_items`/`selected_option` fields in v2 (outcome is free-form `Record<string, unknown>`, `backend/src/v2/call-service.ts:119-122`). |
| `cancel_call` MCP tool → `cancelCall` (`backend/src/voicebridge/service.ts:716`) + `POST /api/v1/calls/:callId/cancel` (`backend/src/routes.ts:481`); `abortCall` (`backend/src/voicebridge/service.ts:776`) for agent-disconnect | **No direct equivalent.** `V2CallService.failCall` exists (`backend/src/v2/call-service.ts:649-668`, emits `call.failed`) but **no route maps to it** — grep over `backend/src` finds `failCall` referenced only in `call-service.ts:649` (definition) and `v2-call-service.test.ts:142-145` (test). `DELETE /api/v2/calls/:callId` → `archiveCall` (`backend/src/v2/routes.ts:477-486`) is terminal-only (throws `InvalidTransitionError` unless `completed`/`failed`, `backend/src/v2/call-service.ts:713-715`) and then drops the aggregate (`backend/src/v2/call-service.ts:720`). There is no v2 cancel-with-note-then-keep-history path and no v2 `aborted` state at all (FSM states are `creating/ringing/connecting/connected/paused/completed/failed`, `backend/src/v2/call-fsm.ts:7-14`). | **Missing (cancel) / unreachable (fail).** |
| v1 `answerCall` (`backend/src/voicebridge/service.ts:536`) + `POST /api/v1/calls/:callId/answer` (`backend/src/routes.ts:505`) | `POST /api/v2/calls/:callId/answer` → `V2CallService.answerCall` (`backend/src/v2/routes.ts:266-274`, `backend/src/v2/call-service.ts:275-311`). Idempotent re-answer (`backend/src/v2/call-service.ts:280`); `connecting` is transient, completes to `connected` immediately (`backend/src/v2/call-service.ts:299-307`, comment: `M1: connecting is transient — media establishment completes immediately (the M2 media gateway will split these into distinct steps)`). Recovered calls stuck in `creating` get distinct `CALL_NEVER_ANSWERED` 409 (`backend/src/v2/call-service.ts:285-291`). | **Equivalent.** |
| v1 phone reply ingest: `POST /api/v1/calls/:callId/user-text` → `processTextMessage` (`backend/src/voicebridge/service.ts:622`, `backend/src/routes.ts:338`) with `client_message_id` dedupe | `POST /api/v2/calls/:callId/utterances` → `submitUtterance` (`backend/src/v2/routes.ts:326-334`, `backend/src/v2/call-service.ts:529-599`) and `POST .../utterances/partial` → `submitUtterancePartial` with `finalize` flag (`backend/src/v2/routes.ts:344-375`, `backend/src/v2/call-service.ts:418-527`). `client_message_id` dedupe via per-call `clientMessageIds` map capped at 10,000 (`backend/src/v2/call-service.ts:485-487`, `564-566`). Emits `speech.started → speech.final → transcript.updated → turn.ended`, clears `aiWaiting` with `turn.lease(active=false)` (`backend/src/v2/call-service.ts:590-593`). | **Equivalent, different protocol.** v1 takes `{ text, client_message_id }`; v2 splits whole-utterance vs streaming-partial paths and adds `utterance_id`/`language`/`start_ms`. Non-finalize partials are explicitly NOT idempotency-wrapped; only finalize is (`backend/src/v2/routes.ts:336-343`). |
| v1 `GET /api/v1/calls/:callId/pending-reply` long-poll helper (`backend/src/routes.ts:403`) | **No equivalent.** No pending-reply route in `backend/src/v2/routes.ts`. The SSE feed (`GET .../events`) is the v2 mechanism for "wait for the next event". | **Missing.** |
| v1 `GET /api/v1/users/:userId/active-call` → `getUserActiveCall` (`backend/src/voicebridge/service.ts:518`, `backend/src/routes.ts:523`) — the fallback-poll and ring-validation dependency | **No equivalent.** No v2 route lists calls by user or returns an active call. v2 has per-call `GET /api/v2/calls/:callId` snapshot (`backend/src/v2/routes.ts:224-264`) only; unknown call → 404. | **Missing.** |
| v1 callback/pause: `scheduleCallback` (`backend/src/voicebridge/service.ts:634`) + `POST /api/v1/calls/:callId/callback` (`backend/src/routes.ts:546`) | **No equivalent.** FSM defines `paused` state and `pause`/`resume` commands (`backend/src/v2/call-fsm.ts:12,22-23,33,81,85-91`), but **no method on `V2CallService` drives them** (grep for `pause|resume` in `backend/src/v2` returns only FSM definitions, the `resume` in `routes.ts:415` cursor-resume comment, and `call-service.ts:372` silence-policy comment). No `POST .../pause` or `.../callback` route exists. | **Missing (engine method + route); FSM-only.** |
| v1 ring dispatch + FCM push: `attemptRing` (`backend/src/voicebridge/service.ts:240`), `pushCallIncoming` (private, called from `attemptRing`), `sendFcmPush` (`backend/src/voicebridge/fcm.ts:72`), `notifyPhone` fan-out, `MAX_RING_RETRIES 12 × RING_RETRY_INTERVAL_MS 15s` | **No equivalent.** Grep for `notifyPhone|sendFcmPush|attemptRing|pushCallIncoming|registerPhone|phoneConnections|sessionWatcher|cancelCallsByAgent|abortCall` in `backend/src/v2` returns only `aiWaiting` field references (turn-lease state), zero transport references. `V2CallService.createCall` emits `call.ringing` as an event (`backend/src/v2/call-service.ts:263-269`, comment: `M1 has no media gateway; the ring event is the contract that M2's transport will consume`) — no code consumes it: no v2→WS push, no v2→FCM send, no v2 ring gate, no v2 retry scheduler. | **Missing entirely.** A v2 call never rings a phone today. |
| v1 session sweeps/staleness: `sweepStaleSessions` (`backend/src/voicebridge/service.ts:882`, pending TTL 3 min / stale-active 30 min), `SessionSweeper` retention (completed 60 min / cancelled 5 min), `PENDING_CALL_TTL_MS`, `CALL_RING_TTL_MS`, `QUEUED_NOTIFICATION_TTL_MS` | `V2CallService.sweepIdleCalls` (`backend/src/v2/call-service.ts:730-749`, wired every `V2_SWEEP_INTERVAL_MS` 5 min in `backend/src/index.ts:449-453` against `V2_CALL_IDLE_ARCHIVE_MS` 24h default) + `archiveCall` (`backend/src/v2/call-service.ts:710-722`). Comment states `Retention sweep (mirrors the v1 stale-session sweeper)... This is retention, not a conversation cap` (`backend/src/v2/call-service.ts:724-729`). A swept call's live TTS stream is hard-cut and its silence timer cleared (`backend/src/v2/call-service.ts:737-738`). | **Partial.** Retention archiving exists; the 3-min pending-ring TTL, the 30-min stale-active auto-complete, the 2-min queued-notification TTL, and the `call_expired` missed-call path have no v2 counterparts. v2's default idle archive is 24h (`backend/src/common/config.ts:110` per prior audit), not 3/30 min. |
| v1 idempotency: `client_message_id` dedupe on user text (in `addMessage`) + phone retry queues | v2 has two layers: (a) per-call `clientMessageIds` map for utterances (capped 10,000, `backend/src/v2/call-service.ts:485-487`); (b) HTTP `Idempotency-Key` header wrapper `withIdempotency` on create/answer/hangup/messages/speak/utterances/finalize-partial (`backend/src/v2/routes.ts:137-174`, applied at `backend/src/v2/routes.ts:198,270,280,290,305,330,363`), backed by `IdempotencyStore` (in-memory, `backend/src/v2/idempotency.ts:39-94`, TTL 24h + 50k-entry cap) or `PostgresIdempotencyStore` (first-write-wins `ON CONFLICT DO NOTHING`, `backend/src/v2/db/pg-idempotency.ts:40-47`), swept on `V2_IDEMPOTENCY_SWEEP_INTERVAL_MS` (`backend/src/index.ts:438-445`). `put` failure after a settled command logs and proceeds without replay protection rather than failing the request (`backend/src/v2/routes.ts:159-167`). | **Equivalent, superset** (HTTP-layer idempotency is v2-only; v1 has no `Idempotency-Key` header mechanism). |
| v1 ownership: per-call `agentId` vs caller identity (`authorizeCall` in tools, `checkCallOwnership` in routes) | `V2CallService.assertAccess` (`backend/src/v2/call-service.ts:187-203`): `service` bypasses; `ai` must match `agentId`; `user`/`device` must match `userId`; `system` can never issue commands. Route actor mapping from v1 auth hook (`backend/src/v2/routes.ts:99-107`). Tested (`v2-call-service.test.ts:163-196` per §4). | **Equivalent.** |
| v1 rate limiting on `/mcp` (120/min) and mutations (60/min) | v2 per-route limits: mutations 60/min (`backend/src/v2/routes.ts:29`), reads 120/min (`backend/src/v2/routes.ts:30`), SSE 10/min (`backend/src/v2/routes.ts:400`). Tested (`v2-routes.test.ts:413-...` per §4). | **Equivalent.** |
| v1 realtime push to phone: `ai_message`/`call_ended`/etc. over WS (`notifyPhone`) + transcript fallback poll | v2 realtime to API consumers: SSE `GET /api/v2/calls/:callId/events` with replay (`none`/`all`/`afterEventId`), `replayMax` cap on full-history reconnects (`backend/src/v2/routes.ts:410-427`, `453-464`), 15s heartbeat (`backend/src/v2/routes.ts:466`), `stream.end` on terminal events (`backend/src/v2/routes.ts:458-461`), ownership pre-check (`backend/src/v2/routes.ts:404-408`). No phone transport attached. | **Different consumer.** SSE serves API/SDK subscribers, not the Android phone (which has no v2 client code, §4). |

### 1.2 Parity summary counts

- **Equivalent (10):** create, send (blocking variant), transcript, hangup/complete, answer, utterance ingest, idempotency (superset), ownership, rate limits, snapshot query.
- **Partial (2):** sweeps/retention (archive exists, ring/active TTLs absent); send-nonblocking (`speak` exists but nothing calls it from MCP/phone).
- **Missing (6):** `send_message_and_wait` blocking-wait semantic; `cancel_call` (distinct cancel vs hangup vs fail); `failCall` route (method exists, unwired); `pending-reply`; `active-call` per-user query; callback/pause-resume (FSM-only); **ring dispatch + FCM push (entire phone delivery path)**.

### 1.3 `stopSpeaking` / barge-in / silence extras (v2-only, no v1 counterpart)

- `POST .../stop-speaking` → `stopSpeaking` (`backend/src/v2/routes.ts:312-324`, `backend/src/v2/call-service.ts:383-410`): hard-cuts live TTS, emits `turn.cancelled(ai_stop)` + `message.failed`, releases lease.
- Barge-in: first partial of an open stream synchronously cuts AI TTS before anything else (`backend/src/v2/call-service.ts:431-444` for partials, `547-559` for whole utterances), emitting `user.interrupted` + `turn.cancelled(barge_in)`. p95 ≤ 50 ms budget lives in `TtsHandle.stop()` (`backend/src/v2/providers.ts:47-54`).
- Silence policy: after an AI turn, `armSilencePolicy` escalates `silence.detected` → `call.noactivity` after `MAX_SILENCE_ESCALATIONS = 3` (`backend/src/v2/call-service.ts:124-125, 828-859`); advisory only, timer-driven via detached emits (`backend/src/v2/call-service.ts:174-183`). Cleared on user speech/hangup/stop (`backend/src/v2/call-service.ts:522, 594, 639`).
- TTS provider seam: `SyncTtsProvider` ($0 on-device default, completes synchronously, `backend/src/v2/providers.ts:70-102`) and `ScriptedTtsProvider` (deterministic tests, `backend/src/v2/providers.ts:116-175`). Audio transport itself (WS media/WebRTC) is M4 future (`backend/src/v2/providers.ts:15`).

### 1.4 FSM states vs v1 statuses

- v1 statuses: `pending/active/paused/completed/cancelled/aborted` (per prior audit). v2 states: `creating/ringing/connecting/connected/paused/completed/failed` (`backend/src/v2/call-fsm.ts:7-14`). v2 has no `cancelled` and no `aborted`; `OPEN_STATES` includes `creating` through `paused` (`backend/src/v2/call-fsm.ts:28-34`); terminal states are absorbing with idempotent same-command no-ops (`backend/src/v2/call-fsm.ts:92-98`).

### 1.5 Stale header note (observed, not a TODO marker)

- `V2CallService` class comment states `M1 is in-memory; persistence lands behind the plane/log at M3` (`backend/src/v2/call-service.ts:130-133`). M3 (Postgres log + idempotency + `recoverAll`/`rehydrate`) is implemented (`backend/src/v2/db/pg-event-log.ts`, `backend/src/v2/db/pg-idempotency.ts`, `backend/src/v2/recovery.ts`, wired at `backend/src/index.ts:375-407`), so this header is outdated. No `TODO`/`FIXME`/`XXX` string exists in `backend/src/v2` (grep for `TODO|FIXME|XXX|HACK|NOT IMPLEMENTED|not yet|unimplemented|stub|placeholder|M4|M5|roadmap` in `backend/src/v2/*.ts` returns only `roadmap M1/M2/M3/M5/R1/R6/R7` milestone references, listed in §4).

### 1.6 Call-graph verdict: v2 is dormant from the live MCP tool surface

**v2 is not wired into the live MCP tool surface at all today.**

- `backend/src/mcp/tools.ts` imports only v1: `VoiceBridgeService`, `VoiceCallSession`, `CallReason` types plus identity/logger/config (`tools.ts` import lines verified via grep this session: `tools.ts:1-8`). All six handlers call `voicebridge.*` methods exclusively (`tools.ts:28-31,56,90,126-129,155-157,193-196,221-224,264-286,303`). The only v2 touchpoint is the `ENGINE_V2` flag read for `send_message_and_wait` timeout math and description text (`backend/src/mcp/tools.ts:236,245,267-280,299,335`) — with the flag on, the handler still calls `voicebridge.registerAiWait`, `voicebridge.createSessionWatcher`, `voicebridge.addAiMessage`, `voicebridge.getCall` (v1 storage, v1 leases, v1 watcher). No `V2CallService`, `EventPlane`, or v2 route is referenced from `tools.ts`.
- `backend/src/mcp/endpoint.ts` imports only `VoiceBridgeService` (`endpoint.ts:16`), constructs tools via `createTools(voicebridge)` (`endpoint.ts:79-85`), and wires `onAgentGone` → `voicebridge.forceDisposeAiWaits` → `voicebridge.cancelCallsByAgent` (`endpoint.ts:139-148`) plus `hasOpenCalls` → `voicebridge.hasOpenCalls` (`endpoint.ts:147`). No v2 reference.
- Reverse direction: nothing in `backend/src/v2/` imports from `../mcp/` or `../voicebridge/` (grep for `mcp|voicebridge` in `backend/src/v2` returns no cross-imports; the only shared import is `config`/`logger`). `V2CallService` never reads `McpSessionRegistry`, never calls `notifyPhone`, never registers an `agentPresenceProvider`.
- The single production edge where the two systems meet is `backend/src/index.ts`: both are constructed (`v2CallService` at `index.ts:396`, `voiceBridgeService` earlier) and both route namespaces are mounted (`registerRoutes` at `index.ts:365`, `registerV2Routes` at `index.ts:409`, `registerMcpEndpoint` at `index.ts:456`). `registerV2Routes` is mounted **always, in every persistence mode** (`index.ts:409` is outside the `persistenceMode === 'v2'` conditional at `index.ts:375`), so `/api/v2/*` answers even when the durable log is not in use — but no MCP tool and no phone flow calls it.
- `backend/src/scripts/db-up.ts` applies only the v2 schema (`db-up.ts:1-21`), the only non-`index.ts` production reference to v2.

**Therefore: v2 is reachable over HTTP (`/api/v2/*`) but unreached by every live caller (MCP tools, Android app). The `ENGINE_V2=true` flag does not route anything to v2; it only changes the v1 wait's timeout math.**

---

## 2. DURABILITY — IS V2 ACTUALLY CRASH-SAFE

### 2.1 Persisted vs memory-only structure inventory

| Structure | Location | Persisted? |
|---|---|---|
| Event log (all domain facts: lifecycle, messages, transcript segments, turn leases, speech) | `PostgresEventLogStore` (`backend/src/v2/db/pg-event-log.ts:80-152`) when `PERSISTENCE_MODE=v2`; `InMemoryEventLogStore` (`backend/src/v2/event-log.ts:28-80`) otherwise | **Yes, in `v2` mode only** (tables `v2_events` + `v2_idempotency`, `backend/src/v2/db/schema.ts:17-49`, applied at `backend/src/index.ts:387`). Per-call `seq` assigned atomically in-INSERT under a transaction-scoped advisory lock (`backend/src/v2/db/pg-event-log.ts:47-64, 83-116`). **No, in every other mode** (process memory, lost on restart). |
| Idempotency responses (HTTP replay) | `PostgresIdempotencyStore` (`backend/src/v2/db/pg-idempotency.ts:15-63`) vs `IdempotencyStore` map (`backend/src/v2/idempotency.ts:39-94`) | **Same split as above** (durable only in `v2` mode; `backend/src/index.ts:388-389` vs `393`). |
| Call aggregates (`V2CallRecord`: state, transcript, `transcriptSeq`, `aiWaiting`, `activeTurn`, `openUtterance`, `connectedAt/endedAt/durationMs/result`, `createdAt/lastActivityAt`) | `V2CallService.calls` map (`backend/src/v2/call-service.ts:136`) | **No — always in-memory.** Rebuilt from the log by `recoverCall`/`recoverAll` (`backend/src/v2/call-service.ts:781-824`) via `rehydrate` (`backend/src/v2/recovery.ts:59-256`). |
| `clientMessageIds` (utterance dedupe map) | `V2CallRecord.clientMessageIds` (`backend/src/v2/call-service.ts:57`) | **No — explicitly not restored.** `rehydrate` constructs a fresh empty map (`backend/src/v2/recovery.ts:98`) and the header documents `does not restore ... clientMessageIds (HTTP-level replay still works through the durable IdempotencyStore)` (`backend/src/v2/recovery.ts:21-24`). |
| Live TTS streams (`call.tts`: handle + `settle`) | `V2CallRecord.tts` (`backend/src/v2/call-service.ts:72`) | **No — explicitly not restored.** Header: `does not restore: live TTS streams (a crashed stream is dead; message.* events carry no aggregate state)` (`backend/src/v2/recovery.ts:21-22`). `TRANSCRIPT_UPDATED` fold covers only settled segments; `MESSAGE_QUEUED/STARTED/FAILED` carry no aggregate state (`backend/src/v2/recovery.ts:229-232`). A `message.started`-without-`completed` turn is dropped on recovery (`v2-recovery.test.ts:77` per §4). |
| Silence escalation counter | `V2CallService.silence` map (`backend/src/v2/call-service.ts:137`) | **No — reset.** Header: `does not restore ... the silence escalation counter (re-armed from 0 with the remaining budget)` (`backend/src/v2/recovery.ts:23-24`). Re-arm candidate (`afterMs`, `armedAt`) is derived from the last `message.completed` (`backend/src/v2/recovery.ts:146-153`), then `recoverCall` re-arms with remaining budget `max(0, silence_after_ms - elapsed)` (`backend/src/v2/call-service.ts:786-789`). |
| Silence re-arm budget source | Last `message.completed` event's `occurred_at` (`backend/src/v2/recovery.ts:146-153`); `CALL_ENDING`/user-turn/terminal events clear the candidate (`backend/src/v2/recovery.ts:121-122, 163-176, 188, 214`) | Derived from the durable log (survives restart); `parseIsoSafe` falls back to `now` on garbage timestamps so a corrupt row fires the first tick immediately rather than poisoning the timer (`backend/src/v2/recovery.ts:46-49`). |
| Open user utterance (in-flight partials) | `call.openUtterance` + local `open` fold state (`backend/src/v2/recovery.ts:33-39, 178-197`) | **Reconstructed from the log** (`speech.started/partial/final` fold, `backend/src/v2/recovery.ts:178-204`); `speech.failed` clears it (`backend/src/v2/recovery.ts:205-211`). Partial text itself is in events, so an unfinalized utterance's text survives; its live streaming context does not. |
| `aiWaiting` lease truth | `call.aiWaiting` boolean (`backend/src/v2/call-service.ts:74`) | **Reconstructed** from the last `turn.lease` event (`backend/src/v2/recovery.ts:155-157`); header lists it under restores (`backend/src/v2/recovery.ts:16`). Note it is a boolean with no expiry — v2 M2 leases have no `active_until` semantics (see §3). |
| `activeTurn` floor holder | `call.activeTurn` (`backend/src/v2/call-service.ts:62`) | **Partially.** `speech.started` sets a user turn (`backend/src/v2/recovery.ts:185-187`); `turn.ended`/`turn.cancelled`/`speech.failed` clear it (`backend/src/v2/recovery.ts:159-176, 205-211`). An AI turn mid-stream at crash time is **not** restored (no `message.started` fold; `call.tts` is `null` post-recovery, `backend/src/v2/recovery.ts:101`). |
| EventPlane subscribers | `EventPlane.subscribers` map (`backend/src/v2/event-plane.ts:34`) | **No — always in-memory.** SSE consumers must reconnect and resume via `?after=`/`Last-Event-ID` (`backend/src/v2/routes.ts:410-427`). |
| EventPlane per-call write serialization | `EventPlane.pendingWrites` map (`backend/src/v2/event-plane.ts:36`, chaining at `48-62`) | **No — in-memory.** Guarantees per-call total order within a process (`backend/src/v2/event-plane.ts:53-54`); cross-process order relies on the Postgres advisory-lock seq allocation (`backend/src/v2/db/pg-event-log.ts:47-55`). |
| Emit-time schema-violation counter | `EventPlane.invalidPayloadCount` (`backend/src/v2/event-plane.ts:43`) | **No** (operational counter only; violations are log-and-continue, `backend/src/v2/event-plane.ts:48-51`). |

### 2.2 Restart trace for a live v2 call (exact path, not extrapolated from v1)

**Case A — `PERSISTENCE_MODE=v2` (durable log):**

1. Pre-crash: every settled command's events are in Postgres before the command returns (outbox: `EventPlane.publish` appends to the log first at `backend/src/v2/event-plane.ts:48-63`, then delivers to subscribers at `65-74`). In-flight-only state (live TTS audio, partial SSE buffers, silence countdown position) is not in the log.
2. Boot: `applyV2Schema` (idempotent DDL, `backend/src/index.ts:387`, `backend/src/v2/db/schema.ts:51-54`), stores constructed over the v2 pool (`backend/src/index.ts:388-389`), then **before accepting traffic** `await v2CallService.recoverAll()` (`backend/src/index.ts:398-407`). Keyset-batched `callIds(500)` enumeration (`backend/src/v2/call-service.ts:797-812`), per-call `rehydrate` fold (`backend/src/v2/recovery.ts:59-256`).
3. Per call: `CALL_CREATED` rebuilds the aggregate (`backend/src/v2/recovery.ts:84-106`); lifecycle events re-drive the FSM with invalid transitions logged-and-skipped (`backend/src/v2/recovery.ts:67-78`); transcript segments re-appended (`backend/src/v2/recovery.ts:217-227`); `aiWaiting` from last `turn.lease` (`backend/src/v2/recovery.ts:155-157`); open utterance from partials (`backend/src/v2/recovery.ts:178-204`); silence re-armed with remaining budget or fired immediately if budget spent (`backend/src/v2/call-service.ts:786-789`, comment at `backend/src/v2/call-service.ts:774-780`).
4. Terminal handling: `CALL_ARCHIVED` in the log → `rehydrate` returns `null`, aggregate stays gone by design (`backend/src/v2/recovery.ts:143-144`); log with no `call.created` → `null`, not rehydratable (`backend/src/v2/recovery.ts:250-253`); corrupt single events skipped, never fatal to boot or sibling calls (`backend/src/v2/recovery.ts:243-247`).
5. Recovery failure semantics: per-event corruption is tolerated inside `rehydrate`, so a `recoverAll` throw means the log itself is unreachable — `index.ts` lets it propagate to the fatal startup handler rather than boot with an empty map that would 404 every live call (`backend/src/index.ts:401-407`).
6. Post-recovery gaps (durable-log mode still loses these): the crashed AI TTS stream is dead — a consumer waiting on `message.completed` for the crashed turn never receives it (the turn's `finished` promise and `emits` queue died with the process; `finishAiTurn` guards `if (!call) return` for archived calls at `backend/src/v2/call-service.ts:996-997`, and no recovery path synthesizes a completion for an interrupted stream). `clientMessageIds` is empty, so a retried whole-utterance `POST` with the same `client_message_id` is **not** deduped at the engine layer post-restart (HTTP `Idempotency-Key` replay still protects only requests that carried that header, `backend/src/v2/routes.ts:137-174`). SSE subscribers must reconnect. The silence counter restarts at 0.

**Case B — any other `PERSISTENCE_MODE` (`memory`, `dual-write`, `database-read`, `database`):**

1. `v2EventLog = new InMemoryEventLogStore()` and `v2Idempotency = new IdempotencyStore()` (`backend/src/index.ts:391-394`).
2. `recoverAll` is **not** called (guarded by `persistenceMode === 'v2'`, `backend/src/index.ts:401`). The fresh process starts with an empty aggregate map and an empty log.
3. Every live v2 call is gone: `getSnapshot`/`getTranscript`/any command throws `CallNotFoundError` → 404 (`backend/src/v2/call-service.ts:205-209`, `backend/src/v2/errors.ts:14-19`). SSE feeds end (subscriber map empty). No v1 `RecoveryManager` involvement — that manager restores v1 sessions only and never touches v2 aggregates.

**RPO/RTO claims as documented in-repo:** `docs/v2/10-roadmap.md:29` records M3 implemented 2026-08-13 with `246 tests green incl. RPO 0/RTO suites — RTO measured ~50 ms for 300+ events`. The worker-kill simulation test asserts exact state equality across a fresh service over the same Postgres log and a 5 s RTO bound (`backend/src/__tests__/v2-recovery.integration.test.ts:10-15,34-80`).

---

## 3. THE ABORT-COUPLING QUESTION — DOES IT EXIST IN V2

### 3.1 Verdict: no — v2 has no transport-liveness-to-call-abort coupling

Trace of every v2 timeout/abort-adjacent path:

1. **MCP session liveness (`McpSessionRegistry.sweepDead` 45s / `sweepExpired` 30 min) only touches v1.** The `onAgentGone` callback force-disposes v1 `aiWaitLeases` then calls `voicebridge.cancelCallsByAgent` (`backend/src/mcp/endpoint.ts:139-148`); `cancelCallsByAgent` iterates `sessionRepo.findByAgentId` (v1 sessions) and calls v1 `abortCall` (`backend/src/voicebridge/service.ts:818-837` per prior audit). v2 aggregates live in `V2CallService.calls` (`backend/src/v2/call-service.ts:136`), invisible to `sessionRepo`; `hasOpenCalls` (the liveness-sweep gate, `backend/src/mcp/endpoint.ts:147`) queries the v1 repo only. A v2-only agent's 45s heartbeat gap therefore cannot close, abort, or otherwise mutate any v2 call — the sweep has no handle on it.
2. **v2 `turn.lease` has no expiry semantics.** `emitTurnLease` always writes `active_until: null` with the comment `the lease has no expiry semantics in M2` (`backend/src/v2/call-service.ts:1039-1054`). The v1 `activeUntil` timestamp + `isLeaseActive` deadline machinery has no v2 counterpart. Nothing in v2 counts down a lease.
3. **v2 silence policy is advisory and never transitions state.** `armSilencePolicy`/`clearSilencePolicy` (`backend/src/v2/call-service.ts:828-865`) emit `silence.detected` and after 3 escalations `call.noactivity` via `emitDetached` (fire-and-forget, `backend/src/v2/call-service.ts:174-183`), then stop (`backend/src/v2/call-service.ts:847-853`, comment: `stop escalating — the AI decides next`). The tick guards `if (!current || !isOpenState(current.state)) return` (`backend/src/v2/call-service.ts:839`) — it only *emits*, never calls `hangupCall`/`failCall`/`archiveCall`. A silent v2 call produces events forever-adjacent (up to 3) and then sits open until retention.
4. **v2 retention (`sweepIdleCalls`) archives, never aborts.** It emits `call.archived` and deletes the aggregate (`backend/src/v2/call-service.ts:730-749`), runs on a 5-min interval against a 24h default (`backend/src/index.ts:449-453`). It is keyed on `lastActivityAt`, not on transport heartbeats. No `aborted` state exists in the FSM (`backend/src/v2/call-fsm.ts:7-14`).
5. **SSE disconnect is a pure unsubscribe.** `close()` unsubscribes, clears the heartbeat, ends the socket (`backend/src/v2/routes.ts:445-451`); socket `close`/`error` both route to it (`backend/src/v2/routes.ts:468-471`). No state mutation, no timer, no abort.
6. **The one place the coupling pattern *appears* to touch v2 is a mirage.** With `ENGINE_V2=true`, `send_message_and_wait` gains `noactivity` escalation (`backend/src/mcp/tools.ts:335-343`) and uncapped waits — but the handler still operates exclusively on v1 storage/leases/watchers (`backend/src/mcp/tools.ts:277-286,303`). A 45s MCP heartbeat gap with `ENGINE_V2=true` still triggers the v1 `sweepDead` → `forceDisposeAiWaits` → `cancelCallsByAgent` path against v1 calls; v2 calls are unaffected because they are never in that repo.

**Stated explicitly:** no 45s (or any-duration) transport heartbeat gap can terminate a v2 call. v2 separates transport liveness from call lifecycle completely — to the point that transport liveness is not observed by v2 at all (no heartbeat input, no presence provider, no `onAgentGone` equivalent anywhere in `backend/src/v2/`).

---

## 4. TEST / PRODUCTION STATUS

### 4.1 Test files (11, all under `backend/src/__tests__/`, `vitest`)

| File | What it covers (from `describe`/`it` names, verified this session) |
|---|---|
| `v2-call-service.test.ts` | Lifecycle (create→ringing→connected, sendMessage turn chain, utterance, hangup+idempotent re-hangup, hangup note, failCall, idle sweep retention), ownership (cross-AI/cross-user reject, service bypass, create-ownership, unknown-call 404), validation (empty content, post-terminal commands), silence/noactivity escalation + clearing + `speak()` arming + sweep hard-cut of live TTS, `CALL_NEVER_ANSWERED` on recovered-`creating` answer |
| `v2-event-log.test.ts` | In-memory log (contiguous seq, cursor replay, unknown-cursor full replay, keyset `callIds` pagination) + EventPlane (live order, replay-then-live, cursor resume exactly-once, unsubscribe, mid-replay buffering order, racing-appends total order, live-vs-buffer race, `replayMax` cap, invalid-payload counting) |
| `v2-fsm.test.ts` | Happy path, incompatible-state rejection, message/utterance allowed on all open states, pause/resume, terminal absorbing + idempotent no-ops |
| `v2-idempotency.test.ts` | In-memory store: oldest-eviction beyond cap, TTL expiry |
| `v2-mcp-lease.test.ts` | `ENGINE_V2` lease-mode `send_message_and_wait`: null-timeout lease capped at `maxTurnLeaseMs`, ceiling expiry, reply with no 45s cap, `noactivity` escalation, legacy 45s clamp with flag off. **Note:** exercises the v1 wait path under the v2 flag, not the v2 engine |
| `v2-pg-event-log.test.ts` | Postgres log: contiguous seq + envelope round-trip, concurrent store-level appends contiguity, cursor resume, list/exists/callIds. DB-gated (see below) |
| `v2-pg-idempotency.test.ts` | Postgres idempotency: store+replay, TTL expiry, first-write-wins races, sweep/size, key-format parity with in-memory |
| `v2-realtime.test.ts` | Streaming speech (`ScriptedTtsProvider`): sendMessage stream chain, `speak()` background completion, live `active_turn` snapshot, `ai_stop` cut, provider failure → `message.failed`+`turn.cancelled`; barge-in (sync cut, `user.interrupted` ordering, `TtsHandle.stop` p95 budget); streaming STT partials (replace-until-finalize, `includePartials` read, barge+finalize atomicity) |
| `v2-recovery.test.ts` | `rehydrate` fold (full lifecycle, crashed-turn drop, in-flight utterance reconstruction, silence budget, silence clearing, archived/empty/`created`-less → null, FSM-invalid tolerance, malformed-payload tolerance, unparseable-timestamp fallback, silence re-arm with remaining budget), `recoverAll` service integration, verifier (gaps/duplicates/contiguity, per-call+total stats, payload violations) |
| `v2-recovery.integration.test.ts` | Simulated worker kill over Postgres (exact-state RPO 0 incl. transcript/aiWaiting/priority), 300+ events RTO < 5 s, verifier clean-vs-injected-gap |
| `v2-routes.test.ts` | REST contract (create+s `events_url`, snapshot, answer, message+utterance+transcript, hangup outcome, cross-AI 403, owning-user answer/hangup, 409 mapping, idempotent create replay + finalized-partial replay, idempotency-put-failure passthrough, cross-identity create 403, archive+404), SSE (lifecycle stream + `stream.end`, `?after=` resume), rate-limit 429 |

### 4.2 What is untested (no test file or case found)

- **Any MCP-tool → v2 path** (none exists to test; `v2-mcp-lease.test.ts` covers the v1 handler under the flag).
- **Ring dispatch / FCM / WS delivery for v2 calls** (no v2 transport code exists).
- **Android app against v2** (no v2 client code exists, §4.4).
- **Pause/resume driven through the engine** (FSM-only; `v2-fsm.test.ts` covers the pure transition, no service method or route).
- **`failCall` over HTTP** (no route; unit-covered only).
- **Multi-instance / cross-process concurrency** beyond the Postgres seq advisory lock (single-instance `EventPlane.pendingWrites` ordering; no test spins two engines against one log).
- **Render idle / cold-start / proxy-timeout behavior** for SSE or long polls.
- PG suites (`v2-pg-event-log`, `v2-pg-idempotency`, `v2-recovery.integration`) run **only when `DATABASE_URL` is set** — `describeDb = hasTestDb ? describe : describe.skip` (`backend/src/__tests__/helpers/v2-pg.ts:12-16`), and `resetV2Db` truncates both v2 tables, requiring a dedicated dev database (`helpers/v2-pg.ts:20-25`).

### 4.3 `ENGINE_V2` / `PERSISTENCE_MODE=v2` deployment status

- `ENGINE_V2` appears in: `backend/src/common/config.ts:92` (default `false`), `backend/src/mcp/tools.ts` (6 reads), `backend/src/voicebridge/service.ts:369` (comment), docs (`docs/v2/10-roadmap.md`, `docs/IMPROVEMENT_BACKLOG.md`, `docs/CURRENT_STATE.md`, prior audit). **It is set to `true` nowhere**: not in `render.yaml` (6 envVars, no `ENGINE_V2`), not in `.env.example:28` / `backend/.env.example:28` (`PERSISTENCE_MODE=dual-write`, no `ENGINE_V2` key at all), not in `infra/k8s/03-configmap.yaml:9` (`PERSISTENCE_MODE: "database"` only), not in `.github/workflows/ci-cd.yml` or `keep-render-warm.yml` (no match), not in `backend/Dockerfile` (no ENV beyond `NODE_ENV=production`).
- `PERSISTENCE_MODE=v2` is accepted by validation (`backend/src/common/config.ts:132`, includes `'v2'`) and implemented in `index.ts:375-407`, and documented as the Phase-3 write-switchover target (`docs/v2/07-migration-plan.md:118`, rollback = flip to `dual-write`, `migration-plan.md:128`). **It is configured nowhere**: `render.yaml:16-17` sets `database`; both `.env.example` files set `dual-write`; k8s sets `database`.
- Only `v2-mcp-lease.test.ts:43-148` flips `config.v2.engineV2` at runtime (test-local mutation). No production, staging, or CI surface sets it.

### 4.4 Android app end-to-end

- **v2 has never been exercised against the real Android app.** `ApiService.kt:96-162` (interface, verified this session) exposes only v1 paths (`calls/{callId}`, `calls/{callId}/messages|complete|cancel|answer|callback|user-text|transcript`, `users/{userId}/active-call`, `phone/register|phone/fcm-token`, `ai/keys`, `agents/{agentId}/status`). Grep for `api/v2|/v2/` across `data/api/ApiClient.kt`, `data/api/ApiService.kt`, `data/repository/CallRepository.kt` returns **zero matches**. No `events_url` consumer, no SSE client, no `utterance_id`/`transcript_seq` handling exists phone-side.

### 4.5 TODO / known-gap markers in `backend/src/v2/`

- Zero occurrences of `TODO`, `FIXME`, `XXX`, `HACK`, `NOT IMPLEMENTED`, `not yet`, `unimplemented`, `stub`, `placeholder` in `backend/src/v2/*.ts` (grep verified; only `roadmap M1/M2/M3/M5/R1/R6/R7` milestone tags, which denote sequencing, not defects).
- Known-gap comments (functional, not markers): `call-service.ts:130-133` (stale M1-in-memory header, §1.5); `call-service.ts:263-264` (ring event unconsumed); `call-service.ts:299-300` (connecting transient until M2 media gateway); `providers.ts:15` (audio transport is M4); `event-plane.ts:27` (Redis Streams adapter at M5); `pg-event-log.ts:12,49` (multi-worker race notes for M5); `schema.ts:4-14` (no FK to a calls table until the projection lands; additive-DDL safety); `recovery.ts:10-25` (explicit restore / does-not-restore contract); `routes.ts:336-343` (non-finalize partials not idempotency-wrapped — deliberate).

---

## 5. MIGRATION COST ESTIMATE (scope only)

### 5.1 Assumed target (for scoping, not a proposal)

MCP tools + Android client run entirely on `V2CallService` + `/api/v2/*`; v1 `aiWaitLeases`/`sessionChangeWaiters` path (`backend/src/voicebridge/service.ts:113-115,327-415`) and its MCP coupling (`backend/src/mcp/endpoint.ts:139-148`) are deleted.

### 5.2 Backend — `tools.ts` (medium-large)

- All six handlers currently call `voicebridge.*` (`tools.ts:28-31,56-364` import/call graph, §1.6). Each needs a v2 equivalent: `create_call` → `v2.createCall` (+ map `reason/summary/options/priority` into v2 input, `routes.ts:42-64` schema); `send_message` → `v2.sendMessage` or `speak` (blocking-vs-streaming choice changes the tool contract); `get_transcript` → `v2.getTranscript` (segment-shape remap `seq/role/type/text` → v1 bubble shape); `complete_call` → `v2.hangupCall` (result-schema remap, `sentiment/action_items` have no v2 field); `cancel_call` → **no target** (requires a new v2 cancel semantic or repurposing `failCall` + wiring a route); `send_message_and_wait` → **no target** (requires either a new blocking-wait over SSE/`turn.lease` inside the tool handler, or replacing the tool with poll guidance — the largest single design item in this file).
- `authorizeCall` (`tools.ts:27-45`) re-targets from `VoiceCallSession.agentId` to `V2CallRecord.agentId` via `getSnapshot` + `assertAccess` semantics (`call-service.ts:187-203`).
- `ENGINE_V2` conditional branches in `send_message_and_wait` (`tools.ts:236,245,267-280,299,335`) collapse once the flag is the only mode.

### 5.3 Backend — `endpoint.ts` + session registry (medium)

- `onAgentGone` → `forceDisposeAiWaits` → `cancelCallsByAgent` (`endpoint.ts:139-148`) is v1-abort coupling; deleting it removes the 45s-abort behavior the phone's `call_aborted`/`AI disconnected` UI depends on (`CallViewModel`/`CallService` abort handlers per prior audit). The registry itself (`session-registry.ts`, heartbeat vs touch, `sweepDead`/`sweepExpired`) stays for MCP session hygiene, but its `hasOpenCalls` gate (`endpoint.ts:147`) must be re-pointed at v2 aggregates or it becomes vacuous (v1 repo empty → gate always false → `sweepDead` never fires, which is also the de-facto decoupling).
- Presence provider wiring (`index.ts:463`: `setAgentPresenceProvider(() => mcpSessions.getActiveIdentities())`) feeds the v1 ring gate; v2 has no ring gate to feed (no consumer).

### 5.4 Backend — v1 service/REST still serving the phone (large)

- The Android app speaks only v1 REST (`ApiService.kt:96-162`, §4.4): `POST user-text`, `GET transcript`, `GET active-call`, `POST answer/cancel/complete/callback`, `GET calls/:id` (with `ai_wait` + `client_info` fields the phone renders). Migrating the phone to v2 requires either (a) rewriting each phone call site to the v2 endpoint + new shapes (`utterances` vs `user-text`, segments vs messages, `events_url`/SSE vs WS events, `active_turn` vs `ai_wait_status` push), or (b) keeping a v1-shaped façade over v2 aggregates. Either way every endpoint in `routes.ts:231-583` and its service methods (`service.ts:462-926` per §1 method map) is in scope until the phone migrates.
- Net-new v2 engine work required before the phone can migrate at all: ring dispatch/FCM consumer for `call.ringing` (nothing consumes it today, §1.1); per-user active-call query; pending-ring TTL + `call_expired` path; callback/pause path (FSM-only today); `ai_wait_status` push shape (v2 `ai_wait.active_until` is always `null`, `routes.ts:244-248`, while the phone's passive lease-expiry tick expects a timestamp); `client_info` caller badge (no v2 field).
- Deleting `aiWaitLeases`/`sessionChangeWaiters` (`service.ts:113-115,327-415`) is a small deletion once no caller remains, but `notifyPhone`/WS fan-out, `attemptRing`/retry scheduler, `pendingNotifications` queue, and `sweepStaleSessions`/`SessionSweeper` retention must stay until the phone leaves v1.

### 5.5 v2 engine gaps to close first (new code, not flag flips)

- `failCall` route wiring; cancel-with-note semantic (v1 `cancelCall(note)` records a user message pre-transition — no v2 equivalent); pause/resume service methods + routes (FSM transitions exist, drivers do not); per-user call lookup; ring/FCM transport; phone-facing push events (`call_expired`, `call_aborted`, `ai_wait_status` with expiry, `call_answered`); `CALL_NEVER_ANSWERED`-class edge coverage for crash-between-`created`-and-`ringing` (exists for answer, `call-service.ts:285-291`, but the orphaned `creating` aggregate then needs a sweeper — none exists).
- `PERSISTENCE_MODE=v2` + `ENGINE_V2=true` configured in `render.yaml`/env/CI (currently set nowhere, §4.3); PG-backed staging soak (PG suites are DB-gated skips locally, `helpers/v2-pg.ts:12-16`).

### 5.6 Relative sizing (rough, no line-by-line)

- **Flag flip + cleanup only: not sufficient.** `ENGINE_V2=true` changes v1 wait math without touching v2 (§1.6); no flag combination routes traffic to v2.
- **Small:** `failCall` route, `speak`-vs-`sendMessage` tool choice, registry-gate re-pointing, lease/watcher deletion after migration.
- **Large:** `send_message_and_wait` replacement over SSE/lease (new blocking primitive or tool-contract change); phone REST migration (every `ApiService.kt` call site + `CallRepository`/`CallService`/`CallViewModel`/`SignalingClient`/`SignalingForegroundService` event handling + abort/offline banners); ring/FCM transport consumer for v2; cancel/pause/active-call parity features.

---

## 6. FILE MAP (every citation reachable)

```
backend/src/v2/call-service.ts       — engine: commands, turns, TTS streaming, silence, recovery, sweep
backend/src/v2/routes.ts             — REST + SSE, zod schemas, idempotency wrapper, rate limits
backend/src/v2/call-fsm.ts           — states, commands, transition table
backend/src/v2/events.ts             — event envelope, M1 catalog, payload schemas
backend/src/v2/event-plane.ts        — outbox publish, per-call order, replay buffering, subscribers
backend/src/v2/event-log.ts          — EventLogStore interface + in-memory store
backend/src/v2/recovery.ts           — rehydrate fold, verifier
backend/src/v2/idempotency.ts        — in-memory idempotency backend + key format
backend/src/v2/providers.ts          — SyncTtsProvider + ScriptedTtsProvider, TtsHandle budget
backend/src/v2/errors.ts             — 404/403/400 error types
backend/src/v2/ids.ts                — uuidV7
backend/src/v2/db/schema.ts          — v2_events + v2_idempotency DDL
backend/src/v2/db/pg-event-log.ts    — Postgres log, advisory-lock seq
backend/src/v2/db/pg-idempotency.ts  — Postgres idempotency, first-write-wins
backend/src/mcp/tools.ts             — v1-only tool handlers + ENGINE_V2 wait math
backend/src/mcp/endpoint.ts          — v1-only session registry + onAgentGone abort coupling
backend/src/index.ts                 — dual construction, conditional durability, always-on v2 routes
backend/src/common/config.ts         — ENGINE_V2 flag, v2 timeouts, persistence modes
backend/src/voicebridge/service.ts   — v1 methods (line map in §1 via grep)
backend/src/routes.ts                — v1 REST surface (line map in §1 via grep)
backend/src/scripts/db-up.ts         — v2 schema applier
backend/src/__tests__/helpers/v2-pg.ts — DB-gated test helper
render.yaml / .env.example / backend/.env.example / infra/k8s/03-configmap.yaml
  — deployment env (no ENGINE_V2 / PERSISTENCE_MODE=v2 anywhere)
mobile/.../data/api/ApiService.kt    — v1-only phone endpoints (no /api/v2 reference)
docs/v2/07-migration-plan.md         — Phase-3 switchover + rollback documentation
docs/v2/10-roadmap.md                — M3 implemented claim + test/RTO numbers
```

*Diagnosis only. No code changed; no migration path proposed. Line numbers correspond to the working tree read this session.*
