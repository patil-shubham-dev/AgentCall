# Fix Report — Stale-Decline Race (cancel kills an answered call) — 2026-09-12

**Bug:** `addMessage` flips a call `pending → active` server-side the moment the
agent sends any message (`backend/src/voicebridge/service.ts:681-686`), while
the phone still shows RINGING. The phone's 60s ring-timeout then fires
`POST /cancel` against a call the backend already considers live, and
`cancelCall` executes it unconditionally — killing a live AI turn. Live
reproduction: call `e2439048` on 2026-09-11 (`docs/LIVE_VALIDATION_2026-09-11.md`
§9) — `CallAnswered` 17:39:04Z from the AI's message, phone ring-timeout
cancel 17:40:26Z. A real caller can hit this, not a test artifact.

**Fix standard:** same as the 09-09 heartbeat/abort work — locate the actual
race and close it, no special-case guard around the one reproduction path.
v1 only. Nothing committed yet (working tree) — deploy decision is the
owner's.

---

## 1. TRACE — WHERE EACH SIDE STANDS (every claim cites the tree)

### 1.1 The phone fires blind — both timeout paths

| Path | Location | State check before firing |
|---|---|---|
| FGS 60s `ringTimeoutJob` | `mobile/…/call/SignalingForegroundService.kt:277-304` | Local `ringingCallId == callId` only (line 280). No server GET. Fires `ACTION_CANCEL_CALL` + `EXTRA_FROM_TIMEOUT=true` (294-299). |
| In-UI 60s countdown auto-decline | `mobile/…/call/IncomingCallActivity.kt:424-433` (timer), `:272-286` (fire) | Local `CallStateHolder` `RINGING` only (272-273). No server GET. (This is the path that fired at 17:40:26Z — 60s after the 17:39:28Z manual activity start.) |
| Backlog-14 local ANSWERED guard | `mobile/…/call/CallService.kt:332-339` (`ACTION_CANCEL_CALL`) | Local `CallStateHolder.ANSWERED` only. Correct spirit, equally blind: in the repro the phone never learned of the answer, so the guard passed. |

`EXTRA_FROM_TIMEOUT` never leaves the device (`CallService.kt:355` uses it
only locally; `attemptCancel` at `:413-423` POSTs `CancelRequest(note)` —
`ApiService.kt:113-117` — and treats any 2xx as success, retrying only on
exception).

### 1.2 The server executes blind — `cancelCall` is last-writer-wins

`backend/src/voicebridge/service.ts:800-852` (pre-fix): terminal states are
no-ops, but **both `pending` and `active` (and `paused`) transition to
`cancelled`** — note written, `call_cancelled` published, metrics bumped.
`withSessionLock` serializes the racing answer and cancel but does not
adjudicate between them: whoever writes last wins, so a timeout arriving 1ms
after the answer kills the call.

### 1.3 The phone can never learn of the answer inside 60s — structural, not a flake

On `pending → active` via message (`service.ts:681-691`): the phone gets
`notifyPhone(ai_message)` **only**. `notifyPhone` (`service.ts:1075-1123`)
sends FCM **exclusively for `call_incoming`** (1090-1092); everything else is
WS-or-queue (2-min TTL). With the WS parked (FCM-only idle), no fallback poll
(FGS dead), and Home polling only `health` + `ai/keys` (never `active-call`),
**zero channels exist** that could deliver the activation inside the 60s
window. (`publishCallAnswered` fires on the event bus, but nothing
phone-facing subscribes with a transport.) Any fix that depends on the phone
*noticing in time* is therefore unsound; the adjudication must happen where
both writes meet — the server row, inside the lock.

### 1.4 What already constrains the blast radius (verified, not assumed)

- Active-call hangup uses `terminateCall → COMPLETE` (`CallService.kt:1107-1123`),
  never cancel — the phone has **no legitimate cancel-of-active path**.
- `cancelCallsByAgent` (disconnect abort) uses `abortCall`, not `cancelCall`
  (`service.ts:917`) — untouched by this fix.
- `sweepStaleSessions` cancels only `pending` (`asExpired`), completes stale
  `active` (`service.ts:981-1011`) — untouched.
- Only remaining cancel-of-active caller: the MCP `cancel_call` tool, whose
  description (`tools.ts:209`, `MCP_API_SPEC.md:27,114,214`) promises
  "pending or active" — a contract that silently discards live conversations
  while `complete_call` exists for ending them.

---

## 2. DECISION — (b) server adjudication now; (a) phone pre-check as follow-up

- **(b) is the source fix:** `cancelCall` executes **only on `pending`**;
  `active`/`paused` get a logged 200 no-op returning the live session.
  Atomic inside the existing `withSessionLock` — a racing answer and cancel
  serialize and the loser sees final truth, so there is **no TOCTOU**, unlike
  any client-side check. Works for every client immediately, including old
  app versions still firing blind timeouts (their stale cancels become
  harmless resolved no-ops instead of kills — and `attemptCancel` treats 2xx
  as resolved, so no retry storm).
- **(a) is optimization, not correction:** a pre-fire `GET call detail` on
  the timeout paths would skip the wasted write in the common case, but keeps
  a residual TOCTOU by itself and needs an app release. Specified precisely
  as follow-up (§5), not implemented here.
- **No flag plumbing** (`from_timeout` end-to-end) was chosen over the
  alternative: it would need the same app release to activate, leaves old
  clients unprotected until then, and adds a three-layer diff to distinguish
  two operations the server can already separate by row state. Decline taps
  while genuinely ringing are unaffected (status still `pending` at execution).

---

## 3. DIFF (working tree, uncommitted)

```
backend/src/voicebridge/service.ts          — cancelCall: live (active/paused) → logged no-op, return live session
backend/src/routes.ts                       — POST /cancel: count sessions.cancelled only on real pending→cancelled transitions
backend/src/mcp/tools.ts                    — cancel_call: pending-only description; live call → error directing at complete_call
MCP_API_SPEC.md                             — §3.6 + table + core-model lines updated to pending-only
backend/src/__tests__/cancel-call-idempotency.test.ts — active-cancel assertion corrected; paused no-op added; race block added
backend/src/__tests__/stale-timeout-cancel.test.ts    — NEW: route-level race + prompt-decline control
backend/src/__tests__/mcp-ownership.test.ts           — tool refuses cancel on live call, points at complete_call
backend/src/__tests__/ai-wait-lease.test.ts           — lease-clear test moved to pending (only cancellable state)
```

Behavioral notes:
- No-op returns the live session with 200 semantics (mirrors the existing
  already-cancelled/completed no-ops): phone retry queues resolve instead of
  retrying; no note is written, nothing is published, metrics don't move.
- MCP `cancel_call` on a live call returns `isError` with
  `already <status> … Use complete_call` — honest contract, no silent strand
  (the call is untouched and completable).
- Deliberate decline-while-ringing (tap or timeout, phone or MCP) is byte for
  byte unchanged: `pending` still cancels with note, publish, and metrics.

---

## 4. TESTS

Full suite: **328 passed, 20 skipped (348)** — was 321/341; +7 new, 2
corrected-for-new-contract. `tsc --noEmit` clean; `eslint` on all touched
files clean. New coverage:

- `cancel-call-idempotency.test.ts` → `stale-decline race` block: exact live
  repro (`pending → addAiMessage → active → cancelCall(note)` asserts status
  stays `active`, `completedAt` undefined, stale note absent, AI turn intact);
  prompt-decline-while-pending control still cancels; `answerCall`-then-cancel
  ordering case (lock serialization).
- `stale-timeout-cancel.test.ts` (new): route-level — `POST /cancel` on an
  AI-answered call returns 200 + `status:active` with the row untouched;
  pending control cancels with note.
- `mcp-ownership.test.ts`: owner `cancel_call` on own active call →
  `isError`, text names `complete_call`, call stays `active`.
- Corrected (old assertions encoded the bug): `cancel-call-idempotency`
  active-cancel case; `ai-wait-lease` lease-clear case moved to `pending`.

**Live-device validation: NOT required — unit coverage is sufficient.**
Justification, stated plainly: the heartbeat fix needed a live phone because
it was about sweep *timing* (45s vs 30-min), FCM/WS *delivery paths*, and
on-device UI state — all transport/timing-dependent. This fix is pure
state-machine adjudication: one row, one lock, no clocks, no network, no
client behavior in the decision. The gate (`status !== 'pending'` inside
`withSessionLock`) is exercised deterministically, including both race orders
and the route/tool surfaces. What a live run would add — watching a phone
fire a now-harmless no-op — verifies nothing the unit tests don't already
pin. The phone-side pre-check follow-up (§5), when implemented, *would*
deserve a live pass since it changes on-device timing behavior.

---

## 5. FOLLOW-UP (specified, not implemented)

Phone timeout paths (`SignalingForegroundService` 60s job,
`IncomingCallActivity` 60s countdown) should `GET calls/{id}` before firing
and skip when the server no longer reports `pending` — removes the wasted
write + local "cancelled" mis-record (today the phone saves cancelled locally
while the server stays active; self-heals via later syncs, but avoidable).
Server already makes this safe to defer: stale cancels are no-ops on arrival.
Estimated ~20 lines Kotlin + a unit test on the ViewModel/service helper; no
backend changes needed.

## 6. FILE MAP

```
backend/src/voicebridge/service.ts       — cancelCall gate (the fix)
backend/src/routes.ts                    — POST /cancel metrics condition
backend/src/mcp/tools.ts                 — cancel_call contract + refusal
MCP_API_SPEC.md                          — §3.6, core model, tool table
backend/src/__tests__/cancel-call-idempotency.test.ts
backend/src/__tests__/stale-timeout-cancel.test.ts  (new)
backend/src/__tests__/mcp-ownership.test.ts
backend/src/__tests__/ai-wait-lease.test.ts
mobile/…/call/SignalingForegroundService.kt:277-304  — blind timeout (traced, unchanged)
mobile/…/call/IncomingCallActivity.kt:272-286,424-433 — blind countdown (traced, unchanged)
mobile/…/call/CallService.kt:330-339,413-423          — local guard + retry queue (traced, unchanged)
```

*Diagnosis + fix. Line numbers are the post-fix tree unless noted. No phone
code changed; no dependency added; no secret touched.*
