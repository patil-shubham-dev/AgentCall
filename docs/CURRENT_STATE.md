# AgentCall — Current-State Architecture Reference

_Verified against the code on 2026-08-19, after the session that shipped: FCM push-to-wake,
MCP session heartbeat/liveness, the call-button removal, quick-replies removal, the global
ringtone, caller badges (clientInfo), MCP config snippets, and the removal of the
`origin='user'` backend branch. Every statement below was checked against the source, not
reconstructed from memory._

---

## 0. The system in one paragraph

A developer runs an MCP client (Claude Desktop, OpenCode, ChatGPT, Cursor, or a custom
client) pointed at the backend's MCP endpoint (`/mcp`), authenticated with a key created in
the phone app ("Add AI"). That client is the **agent**. When the agent calls the
`create_call` tool, the backend asks the phone to ring. The phone plays the ringtone, shows
who's calling (agent name + a badge of which harness it is, when the client announces
itself), and the human can answer, decline, or ask for a callback. Voice and transcript
flow phone-side (Android TextToSpeech + SpeechRecognizer); the backend is a signaling and
state machine that keeps the agent's `send_message_and_wait` loop in sync with the
human's actual participation, and keeps the phone awake with FCM push when the WebSocket
is dead.

---

## 1. MCP connection lifecycle

### Plain language

The agent connects to the backend MCP endpoint with a key. The backend recognizes the
key, creates a private session for it, and names the agent after the key's name (e.g.
"Claude Desktop"). The client's `initialize` handshake may also announce which harness it
is ("ChatGPT", "OpenCode"…); the backend remembers that for the whole session and passes
it along so the phone can show a badge. The session lives until the client explicitly
disconnects, goes silent for 30 minutes, or — if it has a call in flight — stops
heartbeating for 45 seconds. When a session dies, the backend checks whether it was the
agent's last one; if so, any calls that agent had open are aborted so the phone isn't left
hanging.

### Technical detail

**Authentication (`endpoint.ts:resolveIdentity`).** Every request must carry a token:
`Authorization: Bearer`, `x-api-key`, or `?key=`. Resolution order:

1. `token === config.serviceToken` → `{ agentName: DEFAULT_AGENT_NAME ("AI Agent"), via: "service" }`
2. `resolveAiKey(token)` → looks up `sha256(token)` in the `ai_keys` table (or memory in
   dev) → `{ name, keyId, via: "ai_key" }`
3. neither → `401`

Keys are created via `POST /api/v1/ai/keys {name}` (used by Settings → Add AI). Only the
SHA-256 hash is stored; the plaintext key is returned exactly once. The 5-minute
`ONLINE_WINDOW_MS` on `last_used_at` drives the "online" indicator in the app — see §4
for why that's only a hint. The legacy `SERVICE_TOKEN` keeps working and always acts as
the fixed agent "AI Agent".

**Session creation (`endpoint.ts:createMcpSession`).** A POST to `/mcp` without an
`mcp-session-id` header creates a `Server` + `StreamableHttpServerTransport` with a
generated session id, connects them, and registers the session in the shared
`McpSessionRegistry` keyed by that id, storing `agentName` and the parsed identity. The
response carries the `Mcp-Session-Id` header; every subsequent request must echo it.
Unknown session id → `404 SESSION_NOT_FOUND`, which is the MCP-standard "please
re-initialize" signal.

**clientInfo capture (`endpoint.ts:extractClientInfo`).** Only on the `initialize`
request, and only for a fresh session (the very first request). Batch-tolerant (a
`parameters` wrapper is unwrapped), tolerant of absent/empty `clientInfo` and of a
version that is absent or non-string. Captured `{name, version?}` is stored on the
session record, merged into the per-request identity before every tool call
(`decoratedIdentity` + `mcpIdentityStorage.run`), and passed by `create_call` into the
call record — that's how the phone badge works (§3).

**Heartbeats.** Two clocks per session:

- `lastActivityAt` — touched by every request, including `initialize` and tool calls
- `lastHeartbeatAt` — touched only by `notifications/ping` (MCP keepalive)

**Sweeps (`session-registry.ts`).** Two independent loops:

| Sweep | Interval | Closes a session when | Consequence |
|---|---|---|---|
| Idle | 60 s | `lastActivityAt` older than 30 min (`MCP_SESSION_IDLE_MS`) | Clean close; client re-initializes |
| Liveness | 5 s | `lastHeartbeatAt` older than 45 s **and** that agent has open calls (`hasOpenCalls`) | Close + abort the agent's open calls |

The liveness sweep exists to catch `kill -9`, dropped TCP, and crashed clients within
~45 seconds — but only when calls are in flight, because a closed transport that ends the
session is what makes the abort path fire. A quietly-dead agent with no open calls is left
for the 30-minute idle sweep (see §6 weakness: status lags).

**Disconnect handling (`onAgentGone`).** Any close — explicit `DELETE`, idle sweep,
liveness sweep — runs the same logic, but only when the closed session was the **last
live session** for that agent name:

1. `forceDisposeAiWaits(agentName)` — kills any outstanding `send_message_and_wait`
   leases so a stale lease can't shield calls from the abort (see §4).
2. `cancelCallsByAgent(agentName, 'agent_disconnected')` — aborts all that agent's open
   calls: status → `aborted`, phone notified (`call_aborted`, reason
   `agent_disconnected`).

While any other session for the same agent remains, nothing is aborted — the agent is
still alive.

---

## 2. Call initiation — full path

### Plain language

The agent calls the `create_call` tool with the phone's user id, a short reason, and a
summary of why it's calling. The backend marks the call pending, then decides whether the
agent is actually available to talk right now (is a live MCP session present, and isn't
already busy). If yes, the phone is told to ring — via the WebSocket if it's connected,
and via FCM push regardless (that's the part that wakes the phone when the socket is
dead). If the agent isn't available yet, the backend retries quietly for up to 3 minutes;
if nobody ever picks up, the call expires with "missed call" semantics. When the phone
gets the ring — over the socket, via push, or by polling the server as a last resort — it
validates, dedupes, creates the call-history row, and plays the ringtone. Answering
tells the backend to switch the call to active, which is what unblocks the agent's
`send_message_and_wait`. Declining (or the ring timing out) sends the agent a note with
the human's words; "Call back later" sends the agent a request-for-callback note plus a
local reminder.

### Technical detail

**`create_call` tool (`tools.ts`).** Input: `user_id` (required), `reason` (required),
`context.summary` (required), `context.task_id`, `context.options[]`, `priority`
(`normal|urgent`). Output `{callId, status, message, createdAt, expiresAt?}`. The
identity (agent name + clientInfo) comes from AsyncLocalStorage; the tool never accepts a
caller-supplied agent name — identity is server-derived (§3).

**State machine (`voicebridge/service.ts`).** `pending → ringing → active →
completed|aborted|declined`, with `declined` carrying a human note and optional
`callback_requested`. Created via `createCall` (idempotent by `clientCallId`).

**The ring gate (`attemptRing`).** Runs whenever a call enters the window, re-entrant
(can be invoked repeatedly by retries). The ring window is anchored at
`resumedAt ?? createdAt`:

- `now ≥ anchor + 3 min` (`CALL_RING_TTL_MS`) → stop ringing; the pending-TTL sweep
  cancels the call (`call_expired`, phone notified).
- Agent ready (live MCP session present and not already busy in a call) → deliver
  immediately.
- Not ready → schedule a retry: up to `MAX_RING_RETRIES` (12) at
  `RING_RETRY_INTERVAL_MS` (15 s — total ≈ 3 min, exactly matching the TTL), with the
  agent's current state published as `agent_offline` / `agent_busy` on each deferred
  attempt. Retries are scheduled on the shared `cleanupScheduler` (same scheduler as
  callback timeouts) so they're cleaned up on shutdown.

**Delivery (`notifyPhone`, service.ts:885).** For `call_incoming`, three paths:

1. **FCM (always, fire-and-forget)** when `config.fcm.enabled` — `fcm.ts` sends a
   `messages:send` request to `fcm.googleapis.com/v1/projects/<project>/messages:send`
   with a service-account OAuth token; requires `FIREBASE_SERVICE_ACCOUNT_PATH` +
   `FCM_PROJECT_ID`. All payload values are strings (FCM data constraint); nested
   objects are JSON-encoded strings. The target token comes from the `fcm_tokens` table,
   keyed by phone `userId` (registered at `POST /api/v1/phone/fcm-token`).
2. **WebSocket** if the phone's WS is open — replaces the existing connection on
   re-register (`registerPhone`; one live connection per `userId`). WS auth:
   `Authorization: Bearer <phone-token>` or `?token=` (or, in dev, the service token).
3. **Queue** if the WS is closed — `pendingNotifications` per user, pruned to
   `QUEUED_NOTIFICATION_TTL_MS` (2 min), flushed on reconnect. Stale entries are dropped
   silently: **an offline phone whose app is not connected misses rings that arrive more
   than 2 minutes before it reconnects — that's the accepted trade-off, and why FCM is
   the real wake-up path.**

The `call_incoming` payload carries: `callerName` (= the agent id), `summary`, `options`,
`priority`, `clientInfo` (when present), `createdAt`, `expiresAt`.

**Phone receipt — three routes to the same funnel (`SignalingForegroundService.ring()`,
`IncomingCallActivity`):**

- **WS event** — `SignalingClient` parses `call_incoming`, emits `CallIncoming` with
  `clientInfoName` parsed from the nested `clientInfo.name`.
- **FCM push** — `AgentCallMessagingService.onMessageReceived` re-routes the same
  payload through the ring machinery (`ACTION_RING_FROM_PUSH`). `onNewToken` re-registers
  the device token with the backend.
- **Fallback poll** — while the socket is disconnected, an adaptive poll loop calls
  `GET /calls/:id` for pending rings (`checkActiveCall`). Cadence backs off with device
  idle/background time and resets to fast while the app is foregrounded or a ring is
  in flight.

`ringFromEvent` dedupes (recently-rung LRU + expiresAt check), skips quiet-hours calls
(silent channel, auto-decline note), validates status via `getCallStatus` (`pending`/
`active` only), ensures the profile exists (`ensureProfileExists(agentId, callerName)`),
writes the history row immediately (`markCallRinging` — an unanswered ring still shows in
history), and shows the full-screen incoming notification with `client_info_name` as the
badge source. A 60-second ring timeout auto-declines with a note ("no answer").

**Answer** → `POST /calls/:id/answer` → `answerCall` (idempotent; also cancels any
pending callback timer) → status `active` → phone notified → the agent's waiting
`send_message_and_wait` is woken by the session-change watcher and the conversation
proceeds through `addMessage` → watcher wake → tool reply. **Decline / Later** →
`POST /calls/:id/decline {note}` → agent's wait returns the note (and
`callback_requested` when Later is chosen, plus a local reminder on the phone). When the
agent finishes, `complete_call` ends the session; the server-side stale-active sweep
(30 min) auto-completes a call whose agent vanished mid-call (§4).

---

## 3. Agent identity and the caller badge

### Plain language

"Who is calling" is two separate things. The **agent name** — what the phone displays as
the caller and the profile name — comes from the **key name you type in Add AI** ("Claude
Desktop", "Opencode", "Work Assistant"). The **badge** — the little chip with "ChatGPT"
or "OpenCode" under the caller name — is the **harness's own announcement** in its
`initialize` message; the backend stores it per session and forwards it with the call.
Identity is stable across reconnects *as long as the same key is used*: the agent name is
the key's name, and the phone keys its profiles by the slug of that name. The fragile
spots are: two keys with the same name act as one agent; renaming a profile in the app
doesn't rename the key, so the next ring re-creates a duplicate profile; and the harness
name is self-declared — anything can claim to be "ChatGPT".

### Technical detail

**Resolution (`routes.ts` / `endpoint.ts`).** REST and MCP both resolve a token to an
identity `{userId, role, agentId, agentName}`: service token → "AI Agent"; AI key →
`{agentId: keyId, agentName: keyName}`; phone token → `{userId: "solo-user", role:
"phone"}`. The MCP identity additionally carries `clientInfo` (§1) and `via`.

**Storage.** `ai_keys` rows are `{id (uuid), name, key_hash (sha256), created_at,
last_used_at}`. Names are free text, **not unique** (see weaknesses). The phone mirrors
agents as Room profiles keyed by `agentId = callerName.agentSlug()` — single slug
definition in `AgentSlug.kt`: `lowercase()` + whitespace → `-` (`"My Agent"` →
`"my-agent"`). `getOrCreateProfile` creates the profile lazily on first ring, and the
ring always passes the **server-side** name.

**Badge flow.** `initialize.clientInfo{name,version?}` → session record → decorated
identity → `create_call` stamps `session.clientInfo` → `VoiceCallSession.clientInfo` →
WS `call_incoming` payload + `GET /api/v1/calls/:id` `client_info` → phone maps
(`ClientBadge.kt`): `chatgpt`/`openai` → ChatGPT (robot icon), `claude` → Claude (sparkle
icon), `opencode` → OpenCode (terminal), `cursor` → Cursor (terminal), anything else →
generic "AI harness" (computer icon), absent → no badge at all. The incoming-call screen
and the in-call header both show it. Note the fallback-poll and FCM-delivered rings carry
no `clientInfo` in the notification extras — the badge appears once the call UI loads the
call details from the REST endpoint.

**Stability analysis (honest):**

| Scenario | What happens |
|---|---|
| Same key reconnects (new MCP session) | New session id, `clientInfo` re-captured at `initialize`, **same agent name** → same phone profile. Stable. |
| Same name, different key ("Claude Desktop" ×2) | Both resolve to agent name "Claude Desktop" → same phone profile, interleaved history, ring-gate treats them as one presence; the second session's ring shows as if the first agent. **Collision.** |
| `lowercase()`/slug collisions ("My Agent" vs "my agent") | Same profile id `my-agent`. **Collision.** |
| Profile renamed in app | Server key name unchanged → next ring calls `getOrCreateProfile(slug(serverName))` → **new duplicate profile**; the renamed one goes stale. |
| Agent deleted via long-press | Local history deleted + server revocation attempted — **but see §6: the revocation call passes the wrong identifier and silently no-ops.** |
| Harness lies about its name | Nothing verifies `clientInfo` — a custom client can badge itself as "ChatGPT". Self-declared by design. |

---

## 4. Liveness and disconnect detection (the whole picture)

### Plain language

The backend has four independent clocks. (1) An **idle sweep**: any MCP session untouched
for 30 minutes is closed. (2) A **liveness sweep**: if a session's heartbeat stops for 45
seconds *and* it has a call in flight, it's closed — this catches crashes that an idle
timer would miss for 30 minutes. (3) A **lease ceiling**: when an agent waits for a
message with `send_message_and_wait`, its wait normally has no time limit — but the
backend caps the *lease* that shields its calls from aborts at 15 minutes, so a dead
agent can't hide behind a never-ending wait. (4) A **stale-call sweep**: a call with no
activity for 30 minutes is auto-completed. Whenever the *last* session of an agent dies
by any of these routes, the backend force-disposes that agent's message-waits and aborts
its open calls with reason `agent_disconnected`, and the phone shows the call as aborted.
The ring machinery interacts with all of this: an agent that isn't present just gets
deferred rings (up to 3 minutes), and the phone's offline queue holds pushes for 2
minutes max — stale ones are dropped on reconnect.

### Technical detail

- **Idle sweep** — every 60 s, close sessions with `lastActivityAt` ≥ 30 min old.
- **Liveness sweep** — every 5 s, close sessions with `lastHeartbeatAt` ≥ 45 s old
  **and** `hasOpenCalls(agentName)` true. The gate exists so quiet agents aren't closed
  aggressively; its cost is the status-lag weakness in §6.
- **Abort path** — `onAgentGone` fires only on the *last* live session for an agent name,
  then: `forceDisposeAiWaits(agentId)` → `cancelCallsByAgent(agentId,
  'agent_disconnected')` → phone gets `call_aborted`. Order matters: `cancelCallsByAgent`
  normally skips calls with an active `ai_wait` lease (a live waiter mid-turn shouldn't be
  cancelled), so the force-dispose runs first to prevent a stale lease shielding the
  abort.
- **Lease ceiling** — `aiWaitLeases` entries may be created without a timeout
  (`ENGINE_V2` lease semantics); the hard cap is `v2.maxTurnLeaseMs`
  (`V2_MAX_TURN_LEASE_MS`, default 15 min). Disposal is also how a killed agent's stuck
  `send_message_and_wait` returns instead of hanging forever.
- **Stale-active sweep** — calls in `active` with no activity for
  `STALE_ACTIVE_THRESHOLD_MS` (30 min) are auto-completed (agent vanished mid-call
  without a session close — e.g. liveness missed it because it had no *open* session by
  then).
- **Ring interplay** — agent presence for the ring gate comes from the live MCP session
  registry (`getActiveIdentities`), *not* from key last-used timestamps; the 5-minute
  `ONLINE_WINDOW_MS` only drives the app's status dots. Retries: 12 × 15 s against a
  3-minute ring TTL; pending-TTL sweep cancels at 3 min; offline queue TTL 2 min.
- **Phone-side** — the ring itself has a 60-second auto-decline timeout; quiet hours
  ring on a silent channel and auto-decline with a note. The adaptive fallback poll
  covers the "WS dead, FCM off/missed" case.

---

## 5. Everything removed this session — gone vs hidden

### Gone entirely (no residue)

- **Call button** (profile header) and the "Call back" chip — UI, launcher
  (`OutgoingCallLauncher.kt` deleted), outgoing branches of `CallActivity` /
  `CallViewModel` / `CallStateMachine` (state, actions, labels), `ApiService.createCall`
  / `CreateCallRequest` / `CreateCallResponse`. The app can no longer initiate a call.
- **Quick Replies** — composer sheet, `QuickRepliesSheet`, per-profile settings card,
  ViewModel/Repository/DAO methods.
- **Per-agent ringtone picker** — `CallerTuneManager` agent-scoped APIs and
  `AiProfileEntity.ringtoneUri/ringtoneLabel/quickReplies` fields.
- **`origin='user'` backend branch** — `CallOrigin` type, request parsing/validation,
  and the ring-gate skip for user-originated calls (which would have allowed outbound
  rings); the Phase-3 test suite; §6.4 of `REAL_CALL_IMPROVEMENTS.md` marked
  **Removed (2026-08-19)**. Nothing on the app side ever produced `origin='user'`, so no
  live behavior changed — the gate now always applies. `POST /api/v1/calls` itself stays
  (it's the agent-side API), it just has no origin bypass anymore.

### Hidden residue (harmless but present — orphaned state)

| Where | What's left | Why it's harmless |
|---|---|---|
| Room schema (version 3) | `ringtone_uri`, `ringtone_label`, `quick_replies` columns on `profiles` — added by `MIGRATION_1_2`, kept mapped-but-unused in the entity and re-asserted by `MIGRATION_2_3` (guarded adds) so every upgrade path converges on the same schema | Nothing reads or writes them; the guarded adds keep v1→v3, v2-with-columns, and fresh-v2 installs all valid |
| SharedPreferences `caller_tune` | historical per-agent keys (if any were written before removal) alongside the live global `caller_tune_uri` / `caller_tune_label` | Unreachable by current code |
| Backend DB | `ai_keys` rows for agents deleted via Settings *or* via profile long-press are removed (both paths now revoke by key id) | Keyed by the profile's bound `keyId` (reconciled lazily from `GET /api/v1/ai/keys`); unbound/ambiguous names hard-error instead of guessing |
| Call history | History rows + transcripts for deleted agents are deleted locally, but **calls already pushed to the backend** (`call_records`/transcripts server-side) are unaffected by app-side deletion | Server keeps its own record; expected |

Note: the global ringtone (`CallerTuneManager.uri`) and the global call settings
(quick-reply *timing* templates, voicemail-ish message templates) are **not** removed —
only the per-agent variants.

---

## 6. Open weaknesses and fragility (honest)

1. ~~Delete-agent revocation is broken~~ **FIXED 2026-08-19** (root-cause fix shared with #3): `CallRepository.deleteAgent` previously called `api.deleteAiKey(agentName)` — the agent *name* — against the id-keyed endpoint, got a swallowed 404, and deleted locally anyway. Now profiles carry the server's `keyId` (Room v3 + `MIGRATION_2_3`, backfilled by `reconcileProfileKeyIds()` from `GET /api/v1/ai/keys`), and delete/rename/ring-matching all use it. Delete revokes server-side first and **hard-errors with a user-safe message — nothing deleted locally — when the key can't be found (renamed/deleted), the name is ambiguous (duplicate names), or the server call fails**. Rename propagates via the new `PATCH /api/v1/ai/keys/:keyId` before touching the local name, so a renamed agent rings back into the same profile (matched by keyId) instead of duplicating.
2. **Same-name keys collide.** Nothing enforces unique key names. Two keys named "X"
   behave as one agent on the phone (same profile slug, interleaved history, ring-gate
   sees one presence), and deleting "one of them" is ambiguous.
3. **Rename creates duplicates.** Profile rename is local-only; the server key name
   never changes, so the next ring re-creates the profile under the old name.
4. **The badge is self-declared.** `clientInfo` comes from the client's `initialize`
   message; a custom client can present as "ChatGPT". Fine for a convenience badge,
   wrong to trust as identity.
5. **WS auth accepts the service token in production** (`token !== config.serviceToken`
   passes) — anyone holding the service token can join any phone's channel. Phone tokens
   themselves are per-`userId` with no per-user secret.
6. **FCM off ⇒ missed rings.** With FCM disabled, a backgrounded phone whose WS dropped
   relies on the 2-minute queue + reconnect flush; rings older than that are dropped
   silently. FCM is the actual wake-up path and it needs
   `FIREBASE_SERVICE_ACCOUNT_PATH` + `FCM_PROJECT_ID` configured (module logs a warning
   when enabled-but-unconfigured).
7. **Status lag after crash.** The liveness sweep only closes sessions that have open
   calls; a dead agent with no open calls lingers in the registry until the 30-minute
   idle sweep — the app's status dot can say "online" for up to 30 minutes after a
   crash.
8. **Dev-mode in-memory stores.** Without PostgreSQL, `ai_keys`, phone tokens, and FCM
   tokens are in-memory: a backend restart invalidates every key the app has ever issued.
9. **Badge missing on non-WS rings.** FCM-delivered and poll-delivered rings don't carry
   `clientInfo` in the notification extras; the badge only appears after the call screen
   fetches details. Cosmetic, but visible.
10. **Single-instance assumption.** The reply-wakeup is an in-process watcher/event bus;
    horizontally scaled instances would silently miss wakes. Fine for one VPS, wrong for
    a cluster.
11. **Ring window semantics.** The ring window is anchored at `resumedAt ?? createdAt`
    — a call created while the agent was offline and then resumed later rings
    immediately; a call that sat pending >3 min before the agent became ready is
    dropped as expired by design.

---

## 7. If I asked what to improve next (separate from the facts above)

This list is opinion/priority, deliberately separated from the reference sections:

1. ~~Fix the delete-agent revocation bug (#6.1)~~ **DONE 2026-08-19** — see §6.1 for the combined keyId-binding fix (delete + rename + ring matching, with hard errors instead of silent success).
2. **Enforce unique key names** (reject/merge same-name keys) — kills most of the
   identity-collision surface in one move.
3. **Propagate renames or stop pretending** — either rename the server key when the
   profile is renamed, or keep rename local-only and accept the duplicate-profile
   behavior as documented.
4. **Shorten crash-detection lag for agents without open calls** (extend liveness close
   to all sessions after a longer window, e.g. 5 min, instead of only-calls) so status
   dots stop lying.
5. **Document + validate the FCM setup** (a `scripts/` checker for service account /
   project id, and a runtime log line when a ring had to go queue-only) — FCM is the
   linchpin of phone wake-up and the least-tested path on a real device.
6. **Regression tests for the fragile paths**: delete-by-name, same-name keys, rename →
   next ring, and a kill -9 liveness-abort E2E. The ring/liveness machinery has solid
   unit coverage; the identity-collision paths do not.
7. **Consider verifying `clientInfo`** at least enough to avoid badge spoofing for
   known harnesses (e.g. a handshake the official clients use), or mark the badge as
   self-announced in the UI.
