# Real-Call Experience & Call Delivery Overhaul

> **Purpose of this document:** This is the master work-order and future-context reference for
> making AgentCall's phone experience behave like a real phone call. Any future session (agent or
> human) must read this file before touching call delivery, ringing, or call UI, and must update
> the **Status Tracker (Â§9)** when work lands.
>
> **Authored:** 2026-08-11 Â· **Author:** main agent session (deepseek-v4-flash-free)
> **Trigger:** user report â€” *"it doesn't feel like a real call, it feels like a message sent in a
> call wrapper. After connecting the backend, calls meant for days/weeks ago ring instantly and
> repeatedly. Calls should arrive only when the AI agent is online and ready to answer."*
>
> **User's verbatim instruction for this doc:** *"Generate a proper detailed documentation for
> all this phases this would be your future context so you can work on it and will not forget
> what you were doing."*
>
> **Nature of this file:** Markdown documentation only. It imports nothing, defines no code API,
> touches no data schema. It references existing code by file/function anchor so future sessions
> can navigate; consumers are future agent sessions and the human maintainer.

---

## 1. How to use this document

1. Read **Â§2** (current pipeline) and **Â§3** (diagnosis) before changing anything.
2. Implement phases **in order** â€” Phase 1 is the correctness bug, Phase 2 the availability gate,
   Phase 3 the UX. Do not start Phase 3 before Phase 1 ships.
3. Every change lists its exact file + function anchors. Line numbers shift; use the
   function/constant names as the primary anchor.
4. After each phase: run the verification in **Â§7**, then tick the **Status Tracker (Â§9)**.
5. Follow repo rules: `AGENTS.md` (global + project), `docs/IMPLEMENTATION_RULES.md`,
   `docs/IMPLEMENTATION_ROADMAP.md`, commit format `type(scope): description`.
6. Anything ambiguous: ask the user. Do **not** silently pick a different design.

---

## 2. Current pipeline (as of 2026-08-11, post 19-issue fix batch)

### 2.1 Backend delivery flow

```
MCP tool / REST (agent or external caller)
  -> createCall()                     backend/src/voicebridge/service.ts:200
       - creates VoiceCallSession { status: 'pending', createdAt, lastActivityAt }
       - publishCallCreated (event bus, analytics only)
       - notifyPhone('call_incoming', {callId, callerName, reason, summary, options, priority})
         -> if phone WS open: send now           service.ts:578-598
         -> if phone WS closed: queue forever    service.ts:599-606  (pendingNotifications map, in-memory)
  -> registerPhone() (WS 'register' message)     service.ts:540-576
       - on (re)connect: FLUSH ALL queued payloads unconditionally  service.ts:566-575
```

Other producers of phone events:

- `answerCall()` â†’ `call_answered` (service.ts:266+)
- `cancelCall()` / `completeCall()` â†’ `call_cancelled` / `call_ended`
- `pauseCall()` â†’ `callback_scheduled` + scheduler (service.ts:380-395)
- `LifecycleCoordinator.resumeCallback()` â†’ new `call_incoming` with `isCallback: true`
  (lifecycle-coordinator.ts:28-46); pause TTL 24h â†’ `call_expired` (lifecycle-coordinator.ts:48-66)
- `notifyAiWaitStatus()` â†’ `ai_wait_status` (service.ts:181-198) â€” tells the phone whether the AI
  is actively working the call (`registerAiWait` lease, service.ts:~120-168; invoked from
  `mcp/tools.ts:257` via `send_message_and_wait`)

Phone liveness: `phoneConnections` map keyed by userId; `publishPresence*` events exist but only
reflect *phone* WS state, not *agent* readiness.

Session cleanup today: `SessionSweeper` (sweeper.ts:33-47) only deletes sessions whose
`retentionExpiresAt` has passed â€” that field is set for **completed/cancelled** sessions only.
**`pending` sessions have no TTL and live forever.** `STALE_ACTIVE_THRESHOLD_MS = 30 min`
(service.ts:33) filters the `/api/v1/users/:userId/active-call` REST endpoint (routes.ts:494) by
`lastActivityAt`, but nothing else enforces age.

### 2.2 Mobile flow

```
WebSocket message
  -> SignalingClient.handleMessage()      mobile/android/.../call/SignalingClient.kt:230-300
       - 'call_incoming' -> VoiceBridgeEvent.CallIncoming(callId, reason, summary, callerName)
         (payload fields other than these 4 are DROPPED â€” no createdAt/expiresAt today)
  -> events SharedFlow -> SignalingForegroundService.handleEvent()  SignalingForegroundService.kt:86-109
       - CallIncoming  -> ringFromEvent()   :125-139
            GET call status via REST; rings iff status is 'pending' or 'active'
       - Connected     -> ringFromActiveCall()  :111-123
            GET /active-call (30-min activity cutoff); rings if a pending/active call exists
  -> ring(callId, callerName, summary)     :141-169
       - full-screen-intent incoming notification (CallService.showIncomingCallNotification)
       - custom ringtone via CallerTuneManager (settings/CallerTuneManager.kt)
       - TTS prewarm (CallService.ACTION_PREWARM_TTS)
       - 60s auto-decline timer (RING_TIMEOUT_MS) that sends ACTION_CANCEL_CALL
```

In-call: `CallActivity.kt` is **chat-first** â€” message list + text input + option chips + TTS
speech of AI messages (CallService.speakText). There is no full-screen incoming-call activity, no
outgoing/ringback state, no call timer, no mute/speaker, no reconnect-aware in-call state.

### 2.3 Guardrails that already exist (keep them)

- `SignalingClient` reconnect guards: `userDisconnected` flag + `connectGeneration` (prevents
  stale socket callbacks); token in `Authorization` header, never in URL (ApiClient.getWsUrl).
- `CallService` persisted outboxes (pending_answer_ids / pending_cancel_ids /
  pending_complete_ids / pending_callback_ids + `callback:$callId` payloads) with
  `retryWithBackoff` â€” these are **user-intent** retries and MUST stay.
- `ringFromEvent` verifies call status before ringing (prevents ringing already-resolved calls).
- FGS notification text tracks connection state; connect-on-boot after reboot.
- Backend: `withSessionLock`, idempotent answer/cancel/complete, `ai_wait_status` lease.

---

## 3. Problem statement

### 3.1 BUG â€” stale calls ring on reconnect (confirmed root causes)

| # | Root cause | Evidence |
|---|-----------|----------|
| A | **Queued notifications never expire.** `call_incoming` pushed while phone offline sits in `pendingNotifications` with no TTL; reconnect flush (Â§2.1) delivers everything regardless of age (days/weeks later). | service.ts:599-606 (queue), service.ts:566-575 (flush) |
| B | **Payload has no age.** WS envelope `timestamp` is stamped at *send* time (service.ts:588), so the phone cannot distinguish a fresh call from a stale one. `CallIncoming` event drops all extra fields. | service.ts:588; SignalingClient.kt:243-250 |
| C | **`pending` sessions never expire.** A call created a week ago remains `pending`, so the phone's freshness check (`status == pending/active`, SignalingForegroundService.kt:134) passes and it rings. | sweeper.ts:33-47; no pending TTL anywhere |
| D | **Double delivery path.** On reconnect the phone both (1) receives the flushed `call_incoming` AND (2) `Connected` triggers `ringFromActiveCall()` â†’ REST poll â†’ can ring the same/related calls. Multiple queued calls ring back-to-back, each with its own 60s ring + auto-decline. | SignalingForegroundService.kt:88-97, 111-123 |

**User-observed symptom matches exactly:** app offline for days â†’ agent (or scheduled callbacks)
creates calls â†’ phone reconnects â†’ queue flush rings every stale call "instantly and again and
again", with no way for the UI to know they're old.

### 3.2 UX GAP â€” "message in a call wrapper"

- No full-screen incoming-call screen (caller-ID style): just a notification.
- No outgoing "Calling..." + ringback tone for user-initiated calls.
- No call lifecycle states (ringing â†’ connecting â†’ connected/timer â†’ agent speaking â†’ ended).
- In-call UI is a chat transcript, not a call.
- No missed-call handling (a missed call should be a notification + history entry, not a delayed ring).
- No in-call controls (mute, speaker, hold) and no awareness that WS dropped mid-call.

### 3.3 GAP â€” no agent-readiness gate

- Calls ring regardless of whether the AI agent that should handle them is online.
- Agent liveness *signals exist* but are never consulted before ringing:
  - `ai_wait_status` lease (agent actively working the call) â€” service.ts:181-198, tools.ts:257
  - MCP session registry with idle-expiry (`sessions.touch()` on activity,
    mcp/session-registry.ts:32, 52; endpoint.ts:131)
  - `ai_keys` (configured agents, not liveness)

---

## 4. Phase 1 â€” Stop stale calls from ringing (backend + mobile)

> **Goal:** after this phase, a phone that reconnects may only ever ring for a call that is
> genuinely live *right now*. All queued-but-stale events are dropped or converted to a silent
> "missed call" record, never a ring.

### 4.1 Acceptance criteria

- [ ] Phone offline 7 days â†’ 5 agent calls created â†’ reconnect â†’ **zero rings**; backend logs
      show queued payloads expired and dropped (or delivered as missed-call record).
- [ ] Phone offline 30 s â†’ call created â†’ reconnect within 30 s â†’ rings **once**, no double ring
      from `Connected`/`ringFromActiveCall`.
- [ ] Call created, phone online â†’ rings once, immediately.
- [ ] Reconnect storm (WS flaps 10Ã— in a minute) â†’ **at most one** ring for the same callId.
- [ ] No regression: 139 backend tests still pass; new tests added for each change below.

### 4.2 Change 1.1 â€” Queued-notification TTL (backend)

File: `backend/src/voicebridge/service.ts`

- Constants: `QUEUED_NOTIFICATION_TTL_MS = 2 * 60 * 1000` (2 minutes; rationale: only *recently*
  queued events are worth flushing â€” long offline periods must never burst-ring).
- In `notifyPhone()` queue branch (currently line 601-606): while appending, also prune
  `existing` entries older than the TTL (drop before push, so a long-offline backlog shrinks to
  at most the last 2 minutes of events).
- In the reconnect flush (currently line 566-575): filter `queued` by TTL before sending; log
  `droppedCount` + ages. Never delete entries that are younger than the TTL but failed mid-send â€”
  they re-queue via `notifyPhone`'s existing fallback.
- Optional (Phase 1.5): also cap queue length per user (e.g. 50) â€” protects memory if a phone
  stays offline forever.

Test: extend `backend/src/__tests__/notification-queue.test.ts` â€” queue a payload, advance fake
time past TTL (the queue helpers must accept an injectable clock or the test uses
`vi.useFakeTimers`), register phone, assert nothing delivered + `droppedCount` logged; and a
control case inside TTL â†’ delivered.

### 4.3 Change 1.2 â€” Expiry semantics on `call_incoming` (backend + mobile)

Backend â€” `createCall()` (service.ts:237-245) and `handleResume()` (lifecycle-coordinator.ts:35-44):

- Add payload fields: `createdAt` (session `createdAt` ISO), `expiresAt` =
  `createdAt + CALL_RING_TTL_MS` where `CALL_RING_TTL_MS = 3 * 60 * 1000` (constants live next to
  the queue TTL). A call that hasn't been answered within 3 minutes is not a ring-able call.
- `handleResume` reuse: `createdAt = new Date().toISOString()` at resume-fire time (the call is
  "re-created" by the resume).

Mobile â€” `SignalingClient.kt`:

- Extend `VoiceBridgeEvent.CallIncoming` with `createdAtMs: Long?`, `expiresAtMs: Long?`
  (parse via existing `String.toEpochMsOrNull()` helper, line 302).
- In `handleMessage` 'call_incoming' branch (line 243-250): if `expiresAtMs` present and
  `<= System.currentTimeMillis()` â†’ log "stale call dropped" and do NOT emit (defense-in-depth
  for older servers that lack the backend TTL).
- `ringFromEvent` (SignalingForegroundService.kt:125-139): additionally reject when
  `expiresAtMs < now` even if status still says pending (covers the no-pending-TTL window).

### 4.4 Change 1.3 â€” Pending-session TTL (backend)

File: `backend/src/voicebridge/sweeper.ts` + `service.ts` + `lifecycle-coordinator.ts`

- `PENDING_CALL_TTL_MS = 3 * 60 * 1000` â€” a `pending` session that the phone never answered
  becomes `cancelled` (reason `ring_timeout`) with `retentionExpiresAt = now + 5min` (matches
  existing retention pattern, lifecycle-coordinator.ts:57) and `completedAt` set.
- Extend `SessionSweeper` (or a second interval) to flip `pending` sessions older than the TTL:
  update status via repository, then emit `call_cancelled`/`call_expired` through the same
  `notifyPhone` path (queued path is fine â€” it will be TTL-dropped if phone is offline; the
  session is now cancelled so the mobile `pending/active` status check also rejects it).
- `getUserActiveCall()` (service.ts:256-264) already filters by 30-min activity â€” after this
  change it can keep the threshold (belt-and-braces).
- Keep the existing 24 h pause-TTL expiry (lifecycle-coordinator.ts:22-26) unchanged.

Test: new `backend/src/__tests__/pending-call-ttl.test.ts` â€” create call, advance past TTL,
assert status â†’ cancelled, assert phone (connected) receives `call_expired`, assert
`active-call` endpoint no longer returns it; retries (`answerCall` on a cancelled session) are
idempotent no-ops.

### 4.5 Change 1.4 â€” Single delivery path + client dedupe (mobile-first)

- **Remove `ringFromActiveCall()`** (SignalingForegroundService.kt:111-123) as an *automatic*
  connect-time trigger. Rationale: the server push is the single source of truth; the REST poll
  exists only to catch calls created while the app was fully closed â€” after Changes 1.1-1.3 the
  flush already covers that within the TTL window. (Keep the function only if a Phase-1 review
  finds a gap; otherwise delete it.)
- **Recent-rings dedupe set**: in `SignalingForegroundService`, keep `ringingCallId` (existing)
  plus a `recentlyRung: ArrayDeque<String>` capped at ~16 ids with TTL ~5 min
  (`RECENT_RING_TTL_MS`); `ring()` early-returns if `callId` is in the deque. Prevents
  re-ring on WS flaps and repeat flushes.
- `handleEvent` 'Connected' branch: no-op for ringing purposes (delete the `ringFromActiveCall`
  call at line 88-94); log connection only.
- Reconnect storms are additionally bounded by the existing `connectGeneration` guard.

### 4.6 Files touched (Phase 1)

| File | Change |
|------|--------|
| `backend/src/voicebridge/service.ts` | TTL consts; queue prune; flush filter+log; `call_incoming` payload `createdAt`/`expiresAt`; `getUserActiveCall` unchanged |
| `backend/src/voicebridge/lifecycle-coordinator.ts` | `handleResume` payload expiry fields |
| `backend/src/voicebridge/sweeper.ts` | pending-TTL sweep (new interval or extended sweep) |
| `backend/src/__tests__/notification-queue.test.ts` | TTL tests (fake timers) |
| `backend/src/__tests__/pending-call-ttl.test.ts` | new: pending expiry tests |
| `mobile/.../call/SignalingClient.kt` | `CallIncoming` fields + stale-drop in `handleMessage` |
| `mobile/.../call/SignalingForegroundService.kt` | drop connect-time poll; dedupe deque; expiry check in `ringFromEvent` |

### 4.7 Edge cases (must be handled)

- Phone offline at flush â†’ re-queued younger-than-TTL events stay, older are dropped (no infinite loop).
- `call_cancelled`/`call_expired` arriving for a call the phone never saw â†’ app must just clear ring state, no error UI.
- Call answered at second 179 of a 180 s TTL â†’ answer wins; expiry must not cancel an `active` call (pending-TTL sweep checks status === 'pending' only).
- Clock skew: use server time in payloads; phone compares with its own clock â€” a 2-3 min tolerance is acceptable, but TTL math must be generous enough that skew never cancels a live ring (3 min ring TTL vs 2 min queue TTL deliberately overlap).
- DoS: user creates 10k calls while phone offline â†’ queue cap (Change 1.1 optional) prevents unbounded memory.

---

## 5. Phase 2 â€” Deliver calls only when the AI agent is ready

> **Goal:** the phone rings only when the AI agent that owns the call is online *and* actively
> ready to handle it. Otherwise: no ring â€” call stays pending, or becomes a missed-call record.

### 5.1 Liveness signals (already in the codebase â€” reuse, don't reinvent)

1. **AI-wait lease** â€” `registerAiWait(callId, timeoutMs)` (service.ts:~120-168) + `getAiWaitStatus`:
   `active` when lease count > 0 and `activeUntil` not passed. Set from `send_message_and_wait`
   (mcp/tools.ts:257). This is the *best* "agent is working this call" signal.
2. **MCP session** â€” session registry (mcp/session-registry.ts) with idle-expiry; any live session
   for the agent's project = agent process online.
3. **ai_keys** â€” configured agent identities (static, not liveness).

### 5.2 Delivery gate design (backend)

- New concept: `agentReadyFor(call)` resolver â€” `(callId, agentId) => boolean`:
  - `true` if an active `ai_wait` lease exists for `callId` (agent is mid-turn), OR
  - `true` if a live MCP session exists for the agent identity (agent online), else `false`.
  - `true` in **dev mode** (`config.serviceToken === DEV_SERVICE_TOKEN`, mirroring server.ts:100)
    to keep local testing unblocked without an MCP client attached.
- Gate placement: inside `createCall()` **before** `notifyPhone` (service.ts:237), and inside
  `LifecycleCoordinator.handleResume()` **before** the `call_incoming` push (lifecycle-coordinator.ts:35).
  - Ready â†’ push now (existing path).
  - Not ready â†’ skip push; session stays `pending`; register a retry (same `cleanupScheduler`
    pattern as resumeCallback, e.g. `schedule('ring-retry:<callId>', now + 15s, check again)`),
    bounded (max N retries, e.g. 12 â†’ 3 minutes) then cancel + missed-call record.
  - First ring attempt when agent comes online: **each retry must verify the call is still
    `pending` and within ring TTL** â€” reuse Phase 1 constants.
- Publish a `call_delayed`/`call_waiting` event (event-bus) for observability; log reason
  (`agent_offline` vs `agent_busy`).
- The phone already receives `ai_wait_status`; extend it with an `agentOnline` boolean in the
  payload so the UI can show "Agent is connectingâ€¦" while the gate retries.

### 5.3 Missed-call semantics

- If the ring TTL expires before the agent becomes ready (or the phone stays offline):
  - Phone offline â†’ nothing to push (queue TTL drops it). Phone's next reconnect pulls
    `/active-call` â†’ returns nothing â†’ **silent**.
  - Phone online but agent never ready â†’ `call_expired` (`ring_timeout`) pushed; phone shows a
    **missed-call notification** (silent, summary text) instead of ringing, and records it in the
    local call history.
- Phone-side: new event handler for `call_expired` when the call was never ringing â†’ insert
  missed-call row (profile history already supports status labels; see
  ProfileDetailScreen.kt:302 `statusLabel`).

### 5.4 API surface

- `GET /api/v1/users/:userId/active-call` (routes.ts:494): unchanged shape, but now guaranteed to
  only ever return calls that passed (or are waiting on) the agent gate.
- New: `GET /api/v1/agents/:agentId/status` â†’ `{ online, lastSeenAt, currentCallId? }` for the
  Home screen agent chip (HomeViewModel.aiStatus already polls â€” extend it).
- Consider adding `agentReady` to `call_incoming` payload for UI truthfulness.

### 5.5 Tests

- `backend/src/__tests__/agent-ready-gate.test.ts` (new): dev-mode bypass; agent-offline â†’ no
  push + retry scheduled; agent-online â†’ push; retry exhaustion â†’ `call_expired`; phone offline
  whole time â†’ silent no-ring on reconnect (integration with notification-queue TTL).
- Extend `recovery-callback-resumeat.test.ts` for the gated resume path.

---

## 6. Phase 3 â€” Real-call UX (mobile)

> **Goal:** the app feels like a phone. Notification â†’ full-screen incoming call â†’ answer â†’
> voice-first call screen with live state â†’ clean end â†’ history.

### 6.1 Incoming-call screen (new)

- New `IncomingCallActivity` (full-screen intent; existing channel `CHANNEL_INCOMING_CALL` +
  `CallerTuneManager` ringtone + vibration; `AgentCallApp.kt` channel setup stays).
- UI: avatar (initials), caller name (agent profile display name), reason line, big Answer
  (green) / Decline (red) buttons; 60 s countdown ring; **swipe-down-to-dismiss = decline**,
  **swipe-up = answer** optional.
- On open: FGS `ACTION_RING_OPENED` (already exists â€” the ring UI owns the timeout).
- Decline â†’ `ACTION_CANCEL_CALL` + `MessageTemplates.declineMessage` (existing);
  "Call back later" sheet â†’ existing `ACTION_SCHEDULE_CALLBACK` flow (persisted, retried).

### 6.2 Call lifecycle states (in-call screen)

Model a `CallUiState` enum in `CallViewModel` (currently `CallState`-ish fields, see
CallViewModel.kt:44-47 `statusText: "Connecting..."` â€” replace free-text with enum):

`OUTGOING_CALLING â†’ RINGING (agent picking up) â†’ CONNECTING â†’ ACTIVE (timer) â†’ RECONNECTING â†’
ENDED / MISSED`

- Timer: elapsed mm:ss while ACTIVE (start at `connectedAt`).
- `RECONNECTING`: FGS connection state already observable (SignalingClient.connectionState);
  CallViewModel collects it â€” show "Call interrupted â€” reconnectingâ€¦", pause timer.
- `AGENT_SPEAKING / LISTENING`: derive from `ai_wait_status.active` (already an event) â€” show
  animated waveform + label; transcript scrolls in background.
- `ENDED` screen: duration, "Call ended", return to history. `MISSED`: banner + summary.

### 6.3 Voice-first in-call screen (rework `CallActivity.kt`)

- Default collapsed view: large avatar, state label, timer, mute / speaker / end buttons
  (visual pattern exists: `CallControlButton`, CallActivity.kt:590).
- Mute = suppress the agent's TTS locally + indicator (new CallService flag checked in
  `speakText`).
- Speaker toggle = AudioManager.MODE_IN_COMMUNICATION / speakerphone (small `CallAudioManager`
  helper; verify platform APIs before writing — do not invent).
- Transcript behind a "Show transcript" toggle (existing list is the fallback for a11y).

**Implemented (2026-08-12):** header shows agent name (voice-first) with phase-aware label
(Ringing…/Connecting…/timer once ACTIVE); mute control added to the button row
(`CallViewModel.setMuted` → `ACTION_SET_MUTED`); reconnect amber banner; outgoing cancel
button (ACTION_CANCEL_CALL while `phase != ACTIVE`).

### 6.4 Outgoing call flow

- Profile "Call" button → new `CallService.ACTION_OUTGOING_CALL` start: optimistic local
  session (id `out:<uuid>`), CallActivity in `OUTGOING_CALLING`, ringback tone
  (`RingtoneManager.getDefaultUri(TYPE_RINGTONE)` loop or a bundled tone; must stop on
  connect), until backend confirms (first `call_answered` / `ai_message` / `connected` for that
  callId).
- No backend changes strictly required if `createCall` accepts a `caller: 'user'` flag —
  **check `CreateCallInput` shape first** (service.ts + mcp identity) and extend if trivial.

**Implemented (2026-08-12):** profile page Call button launches `CallActivity` with a fresh
UUID `call_id` + `caller_name` + `outgoing=true`; `CallViewModel` starts in
`CallPhase.OUTGOING`; ringback loop plays until the first `call_answered`/`ai_message`
(`CallPhase.ACTIVE`), which fires the existing `ACTION_START_CALL` (markCallAnswered upserts
the call record; ANSWER + voice session). Cancel while ringing sends `ACTION_CANCEL_CALL`
(POST CANCEL + status "cancelled") and closes.

### 6.5 Missed calls & history

- Missed-call notification (silent) + row in profile history with status `missed`
  (ProfileDetailScreen.kt:302 `statusLabel` â€” add mapping).
- "Call back" action on missed-call â†’ starts the normal call flow.

### 6.6 Files touched (Phase 3, indicative)

`IncomingCallActivity.kt` (new), `CallActivity.kt` (rework), `CallViewModel.kt` (state machine),
`CallService.kt` (mute flag, outgoing action), `CallAudioManager.kt` (new, small),
`CallerTuneManager.kt` (reuse), `MainActivity.kt` (routes for IncomingCallActivity),
`ProfileDetailScreen.kt` (missed label), `HomeViewModel.kt` (agent status chip).

---

## 7. Verification plan

### 7.1 Backend (always runnable here)

```bash
cd backend
npm run lint
npm test            # 19 files, 139 tests today; must stay green + new tests
```

### 7.2 Android (NOT runnable on this machine)

- This dev machine cannot build Android: `services.gradle.org` is unreachable (connect timeout),
  Maven Central effectively unreachable, Gradle module cache empty. `platforms;android-35` and
  `build-tools;35.0.0` are installed but dependencies are not.
- Android verification must run on CI or a networked machine:
  `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` (first run needs network to fetch
  Gradle 8.9 + dependencies).
- Manual E2E (after build):
  1. Phone offline (airplane) â†’ create 5 calls via MCP â†’ wait 5 min â†’ reconnect â†’ expect NO rings.
  2. Phone offline 30 s â†’ create call â†’ reconnect fast â†’ exactly 1 ring.
  3. Agent offline â†’ create call â†’ expect no ring, "Agent connecting" state â†’ agent comes online
     â†’ ring fires (Phase 2).
  4. Answer â†’ TTS greeting â†’ timer runs â†’ AI replies spoken â†’ end â†’ history row.

### 7.3 Regression checklist (every phase)

- [ ] `npm run lint` + `npm test` green (backend)
- [ ] No leftover references to removed symbols (grep)
- [ ] Logs: no token in URLs; redacted `[WS] connecting to ...` (substringBefore('?'))

---

## 8. Environment constraints & gotchas

- **No Android build locally** â€” never claim Android verification; say "unverified, needs CI".
- Read tool corruption: if file reads return repeated/fabricated lines, use `git show HEAD:path`
  or small windows before editing; always re-read a file after edits.
- Two subagent attempts in earlier sessions were lost to a working-tree revert â€” for this
  work, apply changes directly and verify with `git diff` per file before moving on.
- New dependencies: verify on registry first (pre-flight-check skill); prefer zero new deps for
  Phase 1; Phase 3 audio helpers use platform APIs only.
- Destructive DB ops / migrations: db-migration-safety skill + explicit user confirmation
  (Phase 1/2 should need no migrations; if a missed-call table is added, it's a new table =
  Room schema change â†’ schemaLocation export + migration).

---

## 9. Status tracker

Update this table as work lands. One row per change; link the commit.

### Phase 1 â€” Stale calls
| # | Change | Status | Notes |
|---|--------|--------|-------|
| 1.1 | Queue TTL + prune + flush filter | âœ… DONE | `service.ts` `QUEUED_NOTIFICATION_TTL_MS=2min`; prune on queue, filter+log at flush; tests in `notification-queue.test.ts` (2 new) |
| 1.2 | `createdAt`/`expiresAt` on `call_incoming` | âœ… DONE | `createCall` + `handleResume` (`CALL_RING_TTL_MS=3min`, exported); mobile `CallIncoming` fields + stale-drop in `handleMessage` + check in `ringFromEvent` |
| 1.3 | Pending-session TTL sweep | âœ… DONE | Implemented in `service.ts` `sweepStaleSessions()` (NOT sweeper.ts â€” it has no cancel/notify path; the 5-min periodic sweep in `index.ts` already calls it). `PENDING_CALL_TTL_MS=3min`, anchored at `resumedAt ?? createdAt`, cancel via idempotent `cancelCall`. `getUserActiveCall` excludes ring-expired pending. Tests: `pending-call-ttl.test.ts` (7) |
| 1.4 | Single delivery path + dedupe deque | âœ… DONE | `ringFromActiveCall` + Connected-trigger deleted; `recentlyRung` deque (5 min / 16 entries) in `ring()`; `CallRepository.checkActiveCall` + `ApiService.getActiveCall` kept (Phase 2 missed-call sync) |
| 1.x | Tests (notification-queue TTL, pending-call-ttl) | âœ… DONE | Backend suite: **20 files / 148 tests green** (was 139); lint clean |

### Phase 2 â€” Agent-ready gate
| # | Change | Status | Notes |
|---|--------|--------|-------|
| 2.1 | `agentReadyFor(call)` resolver | DONE | `service.ts` `isAgentReadyForCall(callId, agentId)`: dev-mode bypass (`DEV_SERVICE_TOKEN`) OR active ai_wait lease (`getAiWaitStatus`) OR live MCP session (`agentPresenceProvider().has(agentId)`); no provider wired -> legacy always-ready (tests/compat) |
| 2.2 | Gate in `createCall` + `handleResume` + ring retry | DONE | `attemptRing(callId, attemptsLeft=MAX_RING_RETRIES)` in `service.ts` (push when ready; reschedule `ring-retry:<callId>` every `RING_RETRY_INTERVAL_MS=15s` x 12 = 3 min window; stops on expiry); `createCall` gated; `LifecycleCoordinator` gets optional `ringCall` hook (index.ts wires `attemptRing`), legacy direct push retained when absent; `index.ts` wires `setAgentPresenceProvider(() => mcpSessions.getActiveIdentities())` + `setRingRetryScheduler(cleanupScheduler)` |
| 2.3 | Missed-call semantics + `call_expired` handling on phone | DONE | Pending-TTL sweep now cancels with `cancelCall(id, undefined, asExpired=true)` -> phone gets `call_expired {reason:'ring_ttl_expired'}` (was `call_cancelled`); explicit declines keep `call_cancelled`. Mobile: new `VoiceBridgeEvent.CallExpired` + `call_expired` parse (SignalingClient.kt), CallService ends call + `saveCallEnded(callId, "expired")` (Room status values now `ended/cancelled/expired`); `ai_wait_status` gains `agentOnline` -> CallActivity banner "Agent is offline..." |
| 2.4 | `GET /agents/:agentId/status` + Home chip | PARTIAL | Endpoint DONE (`routes.ts`: online = live MCP session OR ai-key within online window, `last_seen_at`; new `isAiKeyOnlineByName` in ai-keys.ts + `McpSessionRegistry.getAgentStatus`). Home chip UI deferred to Phase 3 (pure UX; `/api/v1/ai/keys` already feeds HomeViewModel) |
| 2.x | Tests (agent-ready-gate.test.ts) | DONE | `agent-ready-gate.test.ts` (11): immediate push when online, deferral+retry-delivery, expiry stop, ai-wait lease sufficiency, legacy compat, retry exhaustion, gated resume hook + legacy resume, `isAiKeyOnlineByName`, `getAgentStatus`, no-scheduler default. `pending-call-ttl` updated for `call_expired`. Backend suite: **21 files / 159 tests green**; Android `compileDebugKotlin` green |

### Phase 3 â€” Real-call UX
| # | Change | Status | Notes |
|---|--------|--------|-------|
| 3.1 | IncomingCallActivity | DONE | Accept/decline ring UI + 60 s ring timeout; pre-existing, verified |
| 3.2 | Call state machine + timer + reconnect state | DONE | `CallPhase` enum (CONNECTING/OUTGOING/RINGING/ACTIVE/RECONNECTING/ENDED), elapsed timer, reconnect via `SignalingClient.connectionState` collection |
| 3.3 | Voice-first CallActivity + mute/speaker | DONE | Agent-name header, phase-aware label, mute (ACTION_SET_MUTED), speaker (AudioManager), reconnect banner |
| 3.4 | Outgoing call + ringback | DONE | Profile Call button → fresh UUID + `outgoing=true`; OUTGOING phase + ringback until `call_answered`/`ai_message` → `ACTION_START_CALL`; cancel while ringing |
| 3.5 | Missed-call history | TODO | |

### Session log
- **2026-08-12 (backlog)** — Post-Phase-3 improvement items (missed-call history, home status
  chip, summaries, voicemail, quiet hours, per-agent ringtones/quick replies, FSM tests,
  battery audit, v2 audio items) are tracked in
  [`IMPROVEMENT_BACKLOG.md`](./IMPROVEMENT_BACKLOG.md) — read that first for future work.
- **2026-08-11** â€” doc created; diagnosis confirmed (4 root causes); prior 19-issue batch
  complete (signaling auth/header, FGS, Room, callbacks persistence, versions, cleanup); backend
  lint + 139 tests green; Android unverified (network-blocked machine).
- **2026-08-11 (Phase 1)** â€” all 1.1â€“1.4 landed + tests. Backend: queue TTL, ring expiry fields,
  pending-TTL sweep, active-call exclusion. Mobile: stale-drop in SignalingClient, dedupe deque +
  no connect-time poll in FGS (NOT built â€” no Android toolchain on this machine; needs CI
  `./gradlew :app:compileDebugKotlin`). Backend lint clean, 20 files / 148 tests green.

  `./gradlew :app:compileDebugKotlin`). Backend lint clean, 20 files / 148 tests green.
- **2026-08-12 (Phase 2)** - agent-ready gate landed: 2.1 resolver, 2.2 gate+retry (attemptRing, 12x15s), 2.3 missed-call `call_expired` (backend + CallExpired on phone), 2.4 status endpoint (chip UI -> Phase 3). Mobile: agentOnline on ai_wait_status + offline banner. Backend lint clean, **21 files / 159 tests green**; Android `compileDebugKotlin` green (forced --rerun-tasks).
## 10. Open questions (ask the user before Phase 2/3 details)

1. Ring TTL: 3 min acceptable? Or should unanswered rings also auto-cancel server-side
   (currently only the phone's 60 s local timer auto-declines)?
2. "Agent ready" scope: one agent identity per call today (`agentId`), or multi-provider
   routing (see `docs/MULTI_PROVIDER_PLAN.md`)?
3. Missed calls: silent notification only, or also a "call back" deep link?
4. Do we need call *rejection reasons* shown to the user ("Agent offline", "Agent busy")?
5. Outgoing-call backend support: does `CreateCallInput` accept user-initiated calls today, or
   does Phase 3 need a small backend addition?
