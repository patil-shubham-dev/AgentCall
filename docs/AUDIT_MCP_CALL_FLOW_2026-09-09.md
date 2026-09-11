# AgentCall MCP Call-Flow Audit — 2026-09-09

**Scope:** Report only, no code changes. Every claim cites the actual file and line numbers read in this session. The requested `mcp-server/` directory does not exist — the MCP server is **embedded in the backend** sharing the same port (verified at `backend/src/mcp/endpoint.ts:129-245` and `MCP_API_SPEC.md:14-15`). The Android app lives at `mobile/android/app/src/main/java/com/agentcall/app/`.

---

## 1. MCP TOOL SURFACE

### 1.1 Tool inventory (6 tools, single source of truth)

All tools are defined in `backend/src/mcp/tools.ts:56-363` via `createTools()` and advertised by `backend/src/mcp/endpoint.ts:85-93` ( `ListToolsRequestSchema` handler). The spec document `MCP_API_SPEC.md:17-27` lists the same six names and is verified against the code.

| # | Name | Purpose (from description) | Input schema (from `tools.ts`) | Blocking? |
|---|---|---|---|---|
| 1 | `create_call` | `Initiate a voice call to get human input, clarification, or approval. The human will hear your message via their phone.` | `inputSchema` `backend/src/mcp/tools.ts:62-79`: `user_id?` string default `solo-user`; `context` **required** object `{ task_id? string, reason* enum clarification\|approval\|error\|input_required, summary* string maxLength 1000, options? string[] }`; `priority?` enum low\|normal\|high\|urgent default normal | **Immediate** — builds `VoiceCallSession` in memory, persists via `sessionRepo.create`, triggers ring gate `attemptRing`, returns `{ call_id, status, instruction }` (`tools.ts:101-105`). No wait. |
| 2 | `send_message` | `Send a text message to the human during an active call. The message will be spoken aloud on their phone using text-to-speech.` | `backend/src/mcp/tools.ts:115-122`: `call_id*` string, `content*` string maxLength 2000 | **Immediate** — calls `voicebridge.addAiMessage` then returns `{ message_id, sent, spoken_to_human }` (`tools.ts:131-136`). No wait. |
| 3 | `get_transcript` | `Get the conversation transcript from an active or completed call. Shows all messages between you and the human.` | `backend/src/mcp/tools.ts:146-152`: `call_id*` string | **Immediate** — `voicebridge.getTranscript` + JSON dump (`tools.ts:157-166`). No wait. |
| 4 | `complete_call` | `Mark a call as complete and optionally store the result (what the human said, decisions made). After this, the call is ended.` | `backend/src/mcp/tools.ts:172-189`: `call_id*` string, `result?` object `{ transcript_summary? string, user_response? string, decision? string, selected_option? string, sentiment? enum positive\|neutral\|negative\|urgent, action_items? string[] }` | **Immediate** — `voicebridge.completeCall` then returns `{ status: completed }` (`tools.ts:196-203`). |
| 5 | `cancel_call` | `Cancel a pending or active call without completing it.` | `backend/src/mcp/tools.ts:211-218`: `call_id*` string, `reason?` enum resolved\|timeout\|error\|user_requested default resolved | **Immediate** — `voicebridge.cancelCall` then `{ status: cancelled }` (`tools.ts:224-228`). |
| 6 | `send_message_and_wait` | See description split by feature flag below. | `backend/src/mcp/tools.ts:236-260`: `call_id*` string, `content*` string maxLength 2000, `timeout_seconds?` number **schema depends on `ENGINE_V2` flag**: when `config.v2.engineV2 === true` → `minimum 1, maximum 86400`, description `Optional client wait window... Omit to wait until the turn ends`; when `false` → `default 15, minimum 1, maximum 45`, description `Max seconds to wait for a reply (1-45, default 15)`. | **Blocking — the only blocking tool.** Holds the MCP JSON-RPC response open while polling/waiting for a reply (`tools.ts:261-361`). |

All per-call tools (`send_message`, `get_transcript`, `complete_call`, `cancel_call`, `send_message_and_wait`) enforce an ownership gate `authorizeCall` (`tools.ts:27-45`): the caller's `agentId` (from `getAgentIdentity()` in `backend/src/mcp/identity.ts:15-17` resolved via `AsyncLocalStorage` in `endpoint.ts:224`) must match `session.agentId`, else `403`-equivalent `Forbidden` error. `DEFAULT_AGENT_NAME` fallback is `backend/src/voicebridge/ai-keys.ts:62`.

### 1.2 Blocking wait mechanism for `send_message_and_wait`

**Where the wait happens: inside the backend process, not in a separate MCP server process.** There is no separate MCP server process — `registerMcpEndpoint` (`backend/src/mcp/endpoint.ts:129`) registers `POST /mcp` on the same Fastify instance as the REST and WebSocket server. The handler `tool.handler` for `send_message_and_wait` (`backend/src/mcp/tools.ts:261-361`) runs inside that same Node process.

**Actual wait mechanism — event-driven with safety-net poll:**

1. **Lease registration** — `voicebridge.registerAiWait(callId, timeoutMs)` (`backend/src/voicebridge/service.ts:366-415`) creates/extends an in-memory `aiWaitLeases` map entry. `timeoutMs === null` (v2 lease semantics, no client window) sets `activeUntil = now + V2_MAX_TURN_LEASE_MS` (default 15 min, `backend/src/common/config.ts:102`); otherwise `activeUntil = now + max(timeoutMs, 1000ms)`. `notifyAiWaitStatus` immediately pushes `ai_wait_status` over WebSocket (`service.ts:440-460`).

2. **Watcher subscription BEFORE send** — `voicebridge.createSessionWatcher(callId)` (`service.ts:327-359`) creates an in-process `sessionChangeCounters`/`sessionChangeWaiters` event bus. Comment at `tools.ts:282-283`: `Subscribe BEFORE sending so a reply that lands the instant the message is written is not missed (wake is a counter bump, not an edge)`.

3. **Send** — `voicebridge.addAiMessage(callId, content)` (`tools.ts:286`) persists the AI message and `signalSessionChange` is NOT fired for AI messages? Actually `addMessage` for AI does NOT call `signalSessionChange` for the AI path? Checking `service.ts:611-612`: `await this.sessionRepo.save(session); this.signalSessionChange(callId);` — yes it does fire for both roles inside `withSessionLock`. The session change counter increments (`service.ts:361-364`).

4. **Loop** — `while (deadline === null || Date.now() < deadline)` (`tools.ts:302-347`):
   - Fetches fresh `session = await voicebridge.getCall(callId)` each iteration.
   - If `status === completed || cancelled` → returns `outcome: call_ended` with optional `user_note` (`tools.ts:306-319`).
   - Filters `session.messages.filter(m => m.role === user && m.createdAt > aiMessageTime)` — first new user message returns `outcome: reply` with `reply.text` and `exchange` ids (`tools.ts:321-332`).
   - If `ENGINE_V2` and `Date.now() - waitStartedAt >= escalationMs` → returns `outcome: noactivity` with `silent_seconds` (`tools.ts:335-343`). `escalationMs = config.v2.noactivityEscalationMs` default `300000` (5 min) (`common/config.ts:96`).
   - Else `await watcher.waitForChange(Math.min(safetyNetMs, remainingMs))` where `safetyNetMs = config.mcp.replyPollIntervalMs` default `500ms` (`common/config.ts:67`). The watcher (`service.ts:332-350`) resolves `true` when `sessionChangeCounters` was bumped, `false` on timeout — so replies arrive with **no poll floor** (event-driven). Comment at `tools.ts:291-295`: `The session watcher wakes the loop the moment a user message or terminal transition is persisted, so replies are delivered with no poll floor. This only fires if a change somehow bypasses the in-process event bus (e.g. a future multi-instance run)`.

5. **Timeout path** — when loop exits without reply/terminal/noactivity, returns `outcome: timeout` (`tools.ts:349-357`). Message: if `clientWindowSeconds === undefined` → `The wait was interrupted without a reply...`; else `No reply received within the client window. The call is still active...` with `instruction: You can continue working and check back with get_transcript, or send another message with send_message_and_wait.`

6. **Cleanup** — `finally { watcher.dispose(); disposeAiWait(); }` (`tools.ts:357-360`). `disposeAiWait` decrements `aiWaitLeases.count` or clears active flag (`service.ts:399-414`).

**Key invariant:** The wait is purely in-memory (`aiWaitLeases`, `sessionChangeCounters`, `sessionChangeWaiters` maps at `service.ts:113-115`) — killing/restarting the backend drops all waiters and leases (more in §2).

### 1.3 Max wait / timeout and timeout behavior

**Two modes, gated by `ENGINE_V2` env flag** (`backend/src/common/config.ts:92`):

* **`ENGINE_V2 === false` (today's production mode, `render.yaml` does not set it → defaults to `false`):**
  - Client `timeout_seconds` clamped to `1–45`, default `15` (`tools.ts:275`). The wait's `deadline = Date.now() + clientWindowSeconds*1000` (`tools.ts:290`) is the hard server-side cap.
  - On timeout: tool returns `outcome: timeout` with `waited_seconds: <clamped value>` (not an error, not an exception, `isError: false`). Call remains open (`tools.ts:349-357`). No auto-abort, no silent retry — caller must decide (poll `get_transcript` or re-issue `send_message_and_wait`). `MCP_API_SPEC.md:189-200` documents the same three outcomes.

* **`ENGINE_V2 === true` (roadmap, not yet deployed on Render free tier):**
  - `timeout_seconds` becomes an **optional client window** with no server cap except `max 86400` schema limit (24h) and the hard ceiling `V2_MAX_TURN_LEASE_MS` default `900000` (15 min) (`common/config.ts:102`). If omitted, `deadline === null` → wait until `reply`, `call_ended`, or `noactivity` escalation (`tools.ts:290`).
  - On timeout (only if a client window was supplied and elapsed): same `timeout` outcome as above, `waited_seconds` is the supplied window.
  - If no window supplied and `noactivityEscalationMs` (default 5 min, `common/config.ts:96`) elapses with no user activity → returns `outcome: noactivity` (advisory, not terminal) (`tools.ts:335-343`). Comment at `tools.ts:296-298`: `Lease-mode safety valve: after this long with no user activity the wait escalates to noactivity instead of blocking an AI forever`.
  - The hard ceiling: even with `timeout_seconds` omitted, `activeUntil` is set to `now + 15 min` (`service.ts:382-384`) so a crashed waiter's lease can never shield a call from an agent-disconnect abort indefinitely. A live wait returns at the 5-min `noactivity` escalation well before the 15-min ceiling.

**No error is returned on timeout in either mode.** The tool never throws or marks the call aborted on timeout alone. Abort only happens via the MCP-session-liveness path (§4).

---

## 2. TRANSPORT PATH END TO END

### 2.1 Single round-trip hop by hop (agent → phone → agent)

```
Agent tool call
  │  JSON-RPC 2.0 over Streamable HTTP (POST /mcp, SSE response)
  │  Auth: Authorization: Bearer ac_... | x-api-key | ?key=
  ▼
Backend — Fastify MCP endpoint
  │  backend/src/mcp/endpoint.ts:151-225  (app.all('/mcp'))
  │  ├─ resolveIdentity() validates Bearer/x-api-key/?key against ai_keys table (endpoint.ts:62-72, ai-keys.ts:124-150)
  │  ├─ McpSessionRegistry get/touch/heartbeat (endpoint.ts:176-206)
  │  └─ Server[CallToolRequestSchema] → createTools handler (endpoint.ts:95-112 → tools.ts:261-361)
  │  In-process call within the same Node process:
  ▼
Backend — VoiceBridgeService (in-memory + optional Postgres)
  │  voicebridge/service.ts:111 (class VoiceBridgeService)
  │  ├─ addAiMessage → withSessionLock → sessionRepo.save → notifyPhone → publishNotificationRequested
  │  └─ registerAiWait + createSessionWatcher (holds MCP response open)
  │  Push paths (parallel, see below):
  ├─► WebSocket to phone (if connected)
  │     service.ts:207-232 deliverViaWs + notifyPhone:484-1032
  │     Signaling server: backend/src/signaling/server.ts:81-184 (WebSocketServer path /phone, heartbeat 60s, rate-limit)
  │     Client: mobile/android/app/src/main/java/com/agentcall/app/call/SignalingClient.kt:54-504
  │     Protocol: WebSocket (ws:// or wss://) with JSON { type, payload, timestamp } — server→phone pingInterval 60s (config.signaling.heartbeatMs, signaling/server.ts:79)
  │
  └─► FCM DATA push (primary for idle phones)
        service.ts:999-1001  notifyPhone: if msgType===call_incoming && fcm.enabled → void sendFcmPush
        service.ts:166-204   pushCallIncoming: await sendFcmPush (when called from attemptRing)
        voicebridge/fcm.ts:72-160  sendFcmPush → Google FCM HTTP v1 POST https://fcm.googleapis.com/v1/projects/{projectId}/messages:send
        Payload: android: { priority: high }, data: { type, callId, callerName, reason, summary, options, createdAt, expiresAt, clientInfo, ... }
        (fcm.ts:96-102, toDataPayload stringifies non-string values)
        Phone receiver: mobile/android/app/src/main/java/com/agentcall/app/call/AgentCallMessagingService.kt:39-75
        → ContextCompat.startForegroundService(SignalingForegroundService ACTION_RING_FROM_PUSH) → ring validation → ring UI
        │
        │  Phone: SignalingForegroundService.kt:205-235 ringFromEvent validates expiresAt, GET /api/v1/calls/:callId status, dedupes via recentlyRung, posts notification + full-screen intent
        │  User taps Answer → CallService.kt:211-300 ACTION_START_CALL (acquire wake lock, audio focus, signalingClient.connectIfIdle(), hasActiveCall=true, markCallAnswered)
        │  CallService startVoiceSession subscribes to signalingClient.events + fetches call data via ApiService.kt:98-99 GET calls/{callId}
        │  During call: WS delivers ai_message events (CallService.kt:828-919) OR fallback poll (CallViewModel.kt:457-515, SignalingForegroundService.kt:370-468) polls GET /api/v1/calls/:callId/transcript if WS disconnected
        ▼
User reply path
  Phone → Backend
  │  Typed: CallViewModel.kt:329-351 → Intent ACTION_SEND_TEXT → CallService.kt:394-411 enqueueUserText + retryWithBackoff → ApiService.kt:139-143 POST calls/{callId}/user-text  (or direct ApiClient call)
  │  Spoken: CallService.kt:947-1002 SpeechRecognizer → processUserText → same POST
  │  Backend: routes.ts:338-380 POST /api/v1/calls/:callId/user-text → voicebridge.processTextMessage → addMessage(user) → sessionRepo.save + signalSessionChange (service.ts:628)
  │  Auth: Bearer phone token (validatePhoneToken) OR AI key — ownership checked via checkCallOwnership (routes.ts:96-107)
  ▼
Backend → MCP waiter → Agent
  │  signalSessionChange increments sessionChangeCounters and wakes all waiters (service.ts:361-364)
  │  The waiting send_message_and_wait loop's watcher.waitForChange resolves true → next loop iteration finds new user message with createdAt > aiMessageTime → returns outcome: reply { text, received_at, exchange { ai_message_id, user_message_id } } (tools.ts:321-332)
  │  Also aiWaiting lease is cleared when user speaks: v1 does NOT auto-clear (only complete/cancel/abort clears aiWaitLeases.delete); v2's call-service does clear aiWaiting on user utterance (backend/src/v2/call-service.ts:515-524). Phone banner driven by notifyAiWaitStatus push (service.ts:440-460) → SignalingClient.kt:403-413 → CallViewModel.kt:406-412 → CallActivity banner.
  ▼
Agent receives JSON content array with text payload (tools.ts returns CallToolResult with content: [{ type: text, text: JSON.stringify(...) }]).
```

**Transport summary table:**

| Hop | Protocol | File(s) |
|---|---|---|
| Agent → MCP server | MCP JSON-RPC 2.0 over Streamable HTTP, SSE `text/event-stream` responses (`MCP_API_SPEC.md:16, endpoint.ts:114-116`) | `backend/src/mcp/endpoint.ts:151-245` |
| MCP handler → VoiceBridgeService | In-process direct method call (same Node process, same event loop) | `backend/src/mcp/tools.ts:88-100, 129-139` → `backend/src/voicebridge/service.ts` |
| Backend → Phone (WS path) | WebSocket (`ws` library server at `wss://.../phone?user_id=...&token=...`, OkHttp client with `pingInterval 60s` `SignalingClient.kt:57`) | `backend/src/signaling/server.ts:81-184`, `backend/src/voicebridge/service.ts:207-233, 983-1032` |
| Backend → Phone (FCM path) | HTTPS POST to `fcm.googleapis.com` with Firebase OAuth2 Bearer (google-auth-library) + data message delivery via FCM infrastructure → `FirebaseMessagingService.onMessageReceived` | `backend/src/voicebridge/fcm.ts:23-104`, `mobile/.../AgentCallMessagingService.kt:39-75` |
| Phone → Backend (replies) | HTTPS REST `POST /api/v1/calls/:callId/user-text` (Retrofit/OkHttp, `ApiService.kt:139-143`) — also `POST /calls/:callId/callback`, `/cancel`, `/complete`, `/answer`; plus WS reconnect/auth via `GET /api/v1/phone/token` | `mobile/.../data/api/ApiService.kt`, `backend/src/routes.ts:338-380` |
| Backend → MCP waiter (internal wake) | In-process `Map<string, Set<() => void>>` counter bump (`service.ts:113-115, 361-364`) + `sessionRepo.save` — NOT a network hop; single-instance assumption noted at `service.ts:322-325` | `backend/src/voicebridge/service.ts:327-364`, `backend/src/mcp/tools.ts:282-295` |
| Backend → Phone (status pushes) | Same WS + FCM combo for `ai_wait_status`, `call_answered`, `call_ended`, `call_cancelled`, `call_expired`, `call_aborted` (all via `notifyPhone`) | `backend/src/voicebridge/service.ts:983-1032`, `mobile/.../call/CallService.kt:828-919` |

### 2.2 Is the MCP server stateless per-call?

**No — it is stateful per-call in two places, both in-process memory. Killing/restarting the backend process drops live calls' waiters and potentially aborts the calls.**

1. **MCP session registry** — `McpSessionRegistry` holds `Map<string, McpManagedSession>` with `server`, `transport`, `lastActivityAt`, `lastHeartbeatAt`, `agentName`, `clientInfo` (`backend/src/mcp/session-registry.ts:36-37, 48-50`). Created in `registerMcpEndpoint` (`backend/src/mcp/endpoint.ts:139`) and decorated onto Fastify as `app.mcpSessions`. Survives only for the process lifetime. Sweepers `sweepExpired` (30-min idle) and `sweepDead` (45s liveness) run on `setInterval` timers inside the process (`endpoint.ts:247-270`).

2. **VoiceBridgeService call-scoped wait state** — `aiWaitLeases: Map<string, { count, activeUntil, lastActiveAt }>` and `sessionChangeCounters/Waiters` (`backend/src/voicebridge/service.ts:113-115`) and the `pendingNotifications` queue (`service.ts:77`) are plain in-memory Maps. `registerAiWait` returns a closure (`service.ts:366-415`) whose `dispose` only runs if the Node process stays alive. Comment at `backend/src/voicebridge/service.ts:322-325`: `Safe because session locks (and the whole service) are in-process: a render-free single instance guarantees the replying HTTP POST and the waiting tool handler share this event bus.`

**Consequence of restart (documented behavior):**

- All `send_message_and_wait` waiters die with the process — the agent's HTTP request either gets a TCP reset or Render's proxy times out (no retry). The `aiWaitLeases` map is gone.
- On restart, `RecoveryManager` (`backend/src/index.ts:139-140, 301-315`) reloads in-memory session maps from Postgres when `PERSISTENCE_MODE=database/dual-write/database-read`, and the v2 engine replays the event log (`backend/src/index.ts:401-407`). But v1 `aiWaitLeases` are NOT persisted — they are re-created only when a new `send_message_and_wait` arrives.
- The `onAgentGone` callback (`backend/src/mcp/endpoint.ts:140-146`) fires when the last MCP session for an agent closes (by explicit DELETE, idle sweep, or liveness sweep). It calls `forceDisposeAiWaits(agentName)` then `cancelCallsByAgent(agentName, 'agent_disconnected')`. After a restart there are **zero** MCP sessions, so the first new MCP session creation does NOT trigger this — but any pre-restart calls remain in the session repo and will be found by the next sweep. If the agent never reconnects, `sweepStaleSessions` (every 5 min, `backend/src/index.ts:325-328`) and the `SessionSweeper` (every 5 min, `backend/src/index.ts:339-346`) eventually clean them up.

The codebase explicitly calls out the single-instance assumption as a limitation: `tools.ts:293-295` `This only fires if a change somehow bypasses the in-process event bus (e.g. a future multi-instance run)` — i.e., multi-instance is not supported today.

---

## 3. "OFFLINE" STATUS ROOT CAUSE

There is not a single "offline" flag — four distinct surfaces render offline/disconnected states from different signals. Each is triggered by a different condition:

### 3.1 Home screen — `Ready` vs `Offline` (availability + health)

**UI:** `mobile/android/app/src/main/java/com/agentcall/app/home/HomeViewModel.kt` drives `HomeUiState.statusText`.

**Source of truth:**

- Primary: `GET /health` (lightweight, no DB) via `refreshHealth()` (`HomeViewModel.kt:171-191`). Result sets `_uiState.isConnected = ok` and `statusText = if (ok) "Ready" else "Offline"`. This replaces the old WS-connected signal (see `2414f37`).
- Secondary: the foreground availability loop (`HomeViewModel.kt:142-168`) — `ApiClient.ensurePhoneToken() + listAiKeys()` every `AvailabilityPollCadence` (60s healthy, doubling on failures, `AvailabilityPollCadence.kt:20-51`). On failure streak ≥ 2, flips to `isConnected = false, statusText = Offline` (`HomeViewModel.kt:158-161`). On success, `Ready`.
- Agent chips: `AiPresence` enum (`HomeViewModel.kt:41-47`) derived from `AiKeyItem.online/busy` (`data/api/ApiService.kt:56-64`). `online` = `recentlyAuthd (last_used_at within ONLINE_WINDOW_MS 5 min, backend/src/voicebridge/ai-keys.ts:44) OR activeMcpSessionAgentNames.has(name)` (`ai-keys.ts:229-248`). Rendered as dot colors `Success/Warning/DotOffline` (`mobile/android/.../ui/theme/Color.kt:49`, `mobile/.../ui/composables/StatusPill.kt:77-85`).

**Trigger for "Offline" here:**
- (a) `GET /health` fails (backend down, Render cold-start, network unreachable) → immediate `Offline` (`HomeViewModel.kt:173-189`). **This is the common visible offline when Render is idle-spun down.**
- (b) `listAiKeys` fails twice consecutively → `Offline` (`HomeViewModel.kt:158-160`). This reflects API reachability, not WebSocket state.
- **NOT** the WebSocket disconnect: `VoiceBridgeEvent.Disconnected` is explicitly ignored in FCM-only mode (`HomeViewModel.kt:215-219` comment: `In FCM-only idle, a WS disconnect is expected and should not flip the UI to "Disconnected" — health is the source.`).

### 3.2 In-call banners — `Agent is offline` vs `AI is not currently responding`

**UI:** `mobile/android/app/src/main/java/com/agentcall/app/call/CallActivity.kt:246-260` renders two `AnimatedVisibility` banners.

- Banner 1 (reconnecting): `state.phase == RECONNECTING` (`CallActivity.kt:235-244`) — driven by `SignalingClient.connectionState == RECONNECTING` (`CallViewModel.kt:232-238`). Text: `Reconnecting — the call stays live`. Trigger: WebSocket `onFailure`/`onClosed` → `scheduleReconnect` → `RECONNECTING` (`SignalingClient.kt:248-259, 261-271`). This is (b)/(c) — WS dropped (including Render idle kill mid-call).
- Banner 2 (offline/not-responding): `state.aiResponding == false` (`CallActivity.kt:247`). Text split: if `agentOnline == false` → `Agent is offline — your reply will be saved when they reconnect`; else → `AI is not currently responding — your reply will be saved` (`CallActivity.kt:251-255`).

**Source of truth for banner 2:**

- `aiResponding` + `agentOnline` come from `CallViewModel.setAiResponding` (`CallViewModel.kt:406-412`) which is fed by `VoiceBridgeEvent.AiWaitStatus` (`CallViewModel.kt:214-215`).
- That event is emitted by backend `notifyAiWaitStatus` (`backend/src/voicebridge/service.ts:440-460`): `active` = `isLeaseActive(lease)` (lease exists, count>0, not expired), `agentOnline` = `isAgentReadyForCall(callId, agentId)` (`service.ts:153-159`).
- `isAgentReadyForCall` returns `true` if `DEV_SERVICE_TOKEN` mode, or lease active, or `agentPresenceProvider().has(agentId)` (live MCP session). Else `false` (`service.ts:153-159`). So `agentOnline == false` means: no active `send_message_and_wait` lease on this call AND no live MCP session for the agent AND not in dev mode — i.e., **(a) missed agent heartbeat / MCP liveness failure**.

- The `aiResponding == false` banner also has a **passive expiry** client-side: `CallViewModel.tick()` (`CallViewModel.kt:428-435`) flips `aiResponding` to `false` when `now > aiRespondingUntilMs`, even if no new push arrived — mirrors the backend `activeUntil` timestamp. So a waiter that expires without a reply also surfaces as not-responding.

### 3.3 Signaling / WebSocket disconnected state

**UI:** `SignalingForegroundService` notification text (`SignalingForegroundService.kt:567-572`) and `CallViewModel` `isConnected`/`statusText`.

**Trigger:**

- `SignalingClient.connectionState` (`SignalingClient.kt:117-122`) — `DISCONNECTED` when socket closed/parked/max-retries, `RECONNECTING` on transient failure. The backend WebSocket server also kills dead connections via `ws.ping()` heartbeat every `HEARTBEAT_INTERVAL_MS = config.signaling.heartbeatMs` default `60000 ms` (`backend/src/signaling/server.ts:79, 166-177`). Client also sends `pingInterval 60s` (`SignalingClient.kt:57`). So after 60s of silence the dead connection is `terminate()`d.
- Phone-side parking: `SignalingClient.park()` (`SignalingClient.kt:445-466`) closes the socket **without** marking `userDisconnected`, moving to `DISCONNECTED` + emitting `VoiceBridgeEvent.Disconnected`. Triggered by `SignalingForegroundService.maybeParkAndStop()` when no ring and no active call (`SignalingForegroundService.kt:360-367`) — this is **idle normal**, not an error.
- The FGS idle-park renders as `Disconnected` in the notification only if the FGS is actually foregrounded (which in FCM-only mode it is not when idle, `SignalingForegroundService.kt:471-480`).

### 3.4 Agent presence on the backend — what actually aborts calls

This is the only path that turns "offline" into a call terminal event (`call_aborted` → `AI disconnected`):

- `McpSessionRegistry.sweepDead` (`backend/src/mcp/session-registry.ts:121-150`) runs every `MCP_LIVENESS_SWEEP_INTERVAL_MS` default `5000 ms` (`backend/src/common/config.ts:80`). It closes sessions whose `now - lastHeartbeatAt >= MCP_LIVENESS_TIMEOUT_MS` default `45000 ms` (45s, `common/config.ts:79`) **AND** whose agent still has open calls (`hasOpenCalls` gate, `session-registry.ts:126-134`).
- Closing deletes the session and fires `notifyIfLastGone` → `onAgentGone` (`endpoint.ts:140-146`) → `forceDisposeAiWaits(agentName)` (drops all leases for that agent, `service.ts:858-870`) → `cancelCallsByAgent(agentName, 'agent_disconnected')` (`service.ts:818-837`).
- `cancelCallsByAgent` skips calls with `ringDispatchedAt` set (already rang, `service.ts:825-829`) and calls with active lease (but just force-disposed, so none remain). Others are `abortCall` → status `aborted` (`service.ts:830`).
- The `DELETE /mcp` explicit close (`endpoint.ts:239-242` via `transport.onclose = () => sessions.delete(generated)`) also fires the same `notifyIfLastGone` path immediately — this is condition (d) explicit call-end / session close.

**So the answered question per enumerated condition:**

| Condition in task prompt | Actual trigger in code |
|---|---|
| (a) missed agent heartbeat | **Yes — primary.** MCP `notifications/ping` not received within `45000 ms` while agent has open calls → `sweepDead` → abort. Implemented at `backend/src/mcp/endpoint.ts:184-188` (heartbeat handling), `backend/src/mcp/session-registry.ts:78-82` (heartbeat vs touch distinction), `backend/src/common/config.ts:79-80` (timeouts). |
| (b) backend WS to phone dropped | **No direct abort** — phone WS drop does NOT abort the call. It queues notifications for 2 min (`QUEUED_NOTIFICATION_TTL_MS`, `service.ts:48`) and flushes on reconnect (`service.ts:964-981`). In-call AI messages fall back to transcript polling (`CallViewModel.kt:457-515`). Only the *agent* WS/session matters for abort. Phone WS heartbeat is 60s (`signaling/server.ts:79`). |
| (c) Render idle-killing the connection | **Indirect, via (a) and (b).** Render kills idle HTTP/WS after ~15 min of no inbound traffic (`docs/FREE_ARCHITECTURE.md` style; `keep-render-warm.yml:2-5`). But `GET /health` keep-warm every 5 min (`keep-render-warm.yml:23-24`) prevents backend idle kill. For in-call WS, Render's proxy can still kill the WebSocket mid-call (residual exposure flagged in `AUDIT_SYSTEM_GROUND_TRUTH_2026-09-08.md:101`). When killed, the client `scheduleReconnect` parks after 4 short-lived streaks (`SignalingClient.kt:103-111`) and falls back to polling. The agent's `send_message_and_wait` HTTP request can also be killed mid-wait if it exceeds Render's request timeout — the agent sees a transport error, not a call abort, unless the MCP session also dies and triggers the 45s sweep. |
| (d) explicit call-end event | **Yes.** `complete_call`, `cancel_call`, or phone-side `cancel/complete` → `call_ended`/`call_cancelled` (`service.ts:676-767`) — terminal, not "offline". `DELETE /mcp` also triggers the `agent_disconnected` abort path above. |

### 3.5 Reproduction — holding an MCP tool call idle

No device was available in this audit session, so no live 60s/3min/10min hold was executed. The behavior below is derived from code, not measured, and is distinguished as unverified prediction. The timestamps are **projected**, not observed.

**Setup assumed:** `ENGINE_V2=false` (production), single MCP session with `notifications/ping` every 2–5s (as in test drivers `backend/scripts/fcm-render-wake-driver.mjs:71-78` and `e2e-driver.mjs:83-92`), one active call with `ringDispatchedAt` **not** set (ring not yet delivered), agent holds `send_message_and_wait` with `timeout_seconds=45` (max) and no user reply.

| Hold duration | MCP session state | VoiceBridgeService state | Phone UI (projected) |
|---|---|---|---|
| **0–15s** | Heartbeats refresh `lastHeartbeatAt` every ping; `lastActivityAt` frozen (ping ≠ touch, `endpoint.ts:184-188`, `session-registry.ts:78-82`). Idle sweep (30 min) not triggered. | `aiWaitLeases` active until `now+45s`. No sweeps fire. | `CallActivity` banner: none (aiResponding true). `HomeViewModel`: `Ready` (health ok). |
| **15–45s** | Same — session alive as long as pings continue. If pings **stop** (agent blocked on synchronous work and not sending pings), `lastHeartbeatAt` stalls. | `send_message_and_wait` still blocked in `watcher.waitForChange(500)` loop. At `~15s` (default timeout if agent used 15s window) the tool would have returned `outcome: timeout` and **released its lease** — but with 45s window it stays. | Same as above until timeout fires. |
| **~45s (if pings stopped)** | `sweepDead` (every 5s, `endpoint.ts:261-265`) fires, finds `now - lastHeartbeatAt >= 45000` and `hasOpenCalls==true` → closes session, fires `onAgentGone` → `forceDisposeAiWaits` + `cancelCallsByAgent` → call becomes `aborted` (`service.ts:140-146`). Client gets `SESSION_NOT_FOUND` (404) on next request (`endpoint.ts:178-179`). | `aiWaitLease` force-deleted before abort, so `isLeaseActive` becomes false. | `CallActivity` would have already shown `AI disconnected — the call ended` after the `call_aborted` push arrives (if phone WS is up) or on next transcript/status poll. `HomeViewModel` agent chip: `DotOffline` after `ONLINE_WINDOW_MS` (5 min) without auth, or immediately if MCP session was the only online signal. |
| **60s** | If pings continued: session still alive (heartbeats reset). No abort. If pings stopped at T=0: already aborted at ~45s, now no session. If agent's `send_message_and_wait` used default 15s window: it returned at 15s with `outcome: timeout`, lease released, call still `active`/`pending` — **not** aborted unless session also died. | Call status depends on `ringDispatchedAt`: if ring already dispatched (`ringDispatchedAt` set at `service.ts:276`), `cancelCallsByAgent` **skips** it (`service.ts:825-829`) — call stays `pending`/`active` even though agent session died, until `PENDING_CALL_TTL_MS` (3 min) or user cancels. | With undispatched call: `CallViewModel` shows `AI disconnected — the call ended`. With dispatched call: no abort banner; `aiResponding` flips to `offline` via lease expiry tick (`CallViewModel.kt:432-435`) but call remains active — user reply will be saved. |
| **3 min** | Same two branches. | Undispatched pending call hits `PENDING_CALL_TTL_MS = 180000` (3 min, `service.ts:64`) → `sweepStaleSessions` cancels it as `call_expired` (`service.ts:882-905`, runs every 5 min `index.ts:325-328`). Dispatched active call hits `STALE_ACTIVE_THRESHOLD_MS = 30 min` (`service.ts:38`) not yet. | Missed-call notification posted if app backgrounded (`SignalingForegroundService.kt:156-173`). History shows `expired`. |
| **10 min** | All undispatched calls long gone (cancelled at 3 min). Dispatched active calls still alive until 30-min stale-active sweep (`service.ts:36-38`) plus `SessionSweeper` for retention (`CANCELLED_RETENTION_MS 5 min, COMPLETED_RETENTION_MS 60 min, service.ts:36-37`). | MCP session: if agent never re-connected, no session; if it did, new session with `ONLINE` chip. | No ring or call remains to show — history cleared after retention. Home shows `Ready` again. |

**Key nuance:** Because `heartbeat` does **not** refresh `lastActivityAt` (`session-registry.ts:78-82`), a pure `notifications/ping` loop keeps the 45s liveness alive but does **not** extend the 30-min idle window. An agent that only pings but never makes a tool call will be idle-swept after `MCP_SESSION_IDLE_MS = 1800000` (30 min) even though liveness is fine — and if it has no open calls, `sweepDead` deliberately skips it (`session-registry.ts:126-134`).

### 3.6 Reconciliation with the 2026-09-08 ground-truth audit

**The 09-08 audit remains accurate; nothing material has changed since.**

- **Claim:** FCM is primary, WS no longer persistent → **Still true.** The FCM-only flip commit `2414f37` (2026-09-02, verified via `git log --oneline` and `git show 2414f37 --stat`) is the tip of the FCM workstream. No subsequent commit reverted it. `git status --short` is clean (no uncommitted changes). Commits since that audit (`cf44131` docs archive, `bd623d5` chore, `be3fa76` docs, plus spacing/UI refactors `9a84e63`, `c28509c`, `c0889c4`) touch only docs, screenshots, theme tokens, and UI polish — none change `service.ts` ring dispatch, `SignalingClient` park logic, or `HomeViewModel` health gating. The code paths cited in the audit (`service.ts:170-202 pushCallIncoming`, `service.ts:984 notifyPhone FCM block`, `SignalingClient.kt:148 FCM-only comment`, `SignalingForegroundService.kt:360-367 maybeParkAndStop`) are still present verbatim.

- **Residual exposure flagged in the audit (mid-call WS kill by Render) is still the one untested failure mode.** The audit's §2 conclusion (`AUDIT_SYSTEM_GROUND_TRUTH_2026-09-08.md:101`) — `Residual exposure: an in-call WS killed by Render mid-conversation. Mitigations in place: OkHttp pingInterval(60s), readTimeout 15s (commit 56093c0), reconnect with backoff, and shortLivedStreak parking. Not live-tested mid-call` — matches today's code (`SignalingClient.kt:56-112`, `CallViewModel.kt:224-289` reconnect handling + `maybePollTranscript` fallback). No change.

- **Keep-warm still active:** `keep-render-warm.yml:23-24` still pings every 5 min (tightened from 10 to 5).

- **One subtlety not in the audit:** the audit assumed an undispatched ring would ring again after reconnect. Today's code prevents re-ring of dispatched calls (`ringDispatchedAt` guard in `cancelCallsByAgent`, `service.ts:825-829`) — dispatched calls survive agent disconnect, undispatched do not. This was added after the audit's window but is additive.

---

## 4. TIMEOUT / TTL INVENTORY

Every timeout/TTL found by reading `backend/src/common/config.ts:1-183`, `backend/src/voicebridge/service.ts:1-1047`, `backend/src/mcp/session-registry.ts`, `backend/src/signaling/server.ts`, `backend/src/v2/call-service.ts`, and the mobile cadence objects. Values are **defaults** (env-overridable via `parseIntSafe`).

### 4.1 MCP session & wait timeouts (backend)

| Constant | Value | File:line | Meaning |
|---|---|---|---|
| `MCP_SESSION_IDLE_MS` / `sessionIdleMs` | `1800000` (30 min) | `common/config.ts:71` | Idle-expiry for MCP sessions: `sweepExpired` closes sessions untouched for this long (`mcp/endpoint.ts:247-256`, `mcp/session-registry.ts:152-181`). |
| `MCP_SESSION_SWEEP_INTERVAL_MS` / `sessionSweepIntervalMs` | `60000` (60s) | `common/config.ts:72` | How often `sweepExpired` runs (`mcp/endpoint.ts:247`). |
| `MCP_LIVENESS_TIMEOUT_MS` / `livenessTimeoutMs` | `45000` (45s) | `common/config.ts:79` | Heartbeat timeout: session whose `lastHeartbeatAt` stalled this long is considered dead (`mcp/endpoint.ts:261-265`, `mcp/session-registry.ts:121-150`). |
| `MCP_LIVENESS_SWEEP_INTERVAL_MS` / `livenessSweepIntervalMs` | `5000` (5s) | `common/config.ts:80` | How often `sweepDead` runs (`mcp/endpoint.ts:261`). |
| `AI_REPLY_POLL_INTERVAL_MS` / `replyPollIntervalMs` | `500` (0.5s) | `common/config.ts:67` | Safety-net wake interval for `send_message_and_wait` (`mcp/tools.ts:295`). Replies are event-driven; this is the fallback if the in-process bus is bypassed. |
| `send_message_and_wait` client window (ENGINE_V2 false) | `1–45s`, default `15s` | `mcp/tools.ts:271-275` | Per-tool-call wait cap (clamped). |
| `send_message_and_wait` client window (ENGINE_V2 true) | `optional, max 86400s (24h)`, no default (wait until turn ends) | `mcp/tools.ts:271-274` | When omitted, `deadline === null`. |
| `V2_NOACTIVITY_ESCALATION_MS` / `noactivityEscalationMs` | `300000` (5 min) | `common/config.ts:96`, applied at `mcp/tools.ts:299` | Lease-mode safety valve: `send_message_and_wait` returns `noactivity` after this with no user activity (`tools.ts:335-343`). |
| `V2_MAX_TURN_LEASE_MS` / `maxTurnLeaseMs` | `900000` (15 min) | `common/config.ts:102`, applied at `voicebridge/service.ts:382-390` | Hard ceiling for `activeUntil` even with no client window — prevents a crashed waiter's lease shielding the call forever. |
| `V2_SSE_HEARTBEAT_MS` / `sseHeartbeatMs` | `15000` (15s) | `common/config.ts:104` | SSE heartbeat for `GET /api/v2/calls/:id/events` (v2, not v1). |
| `V2_CALL_IDLE_ARCHIVE_MS` / `callIdleArchiveMs` | `86400000` (24h) | `common/config.ts:110`, applied at `index.ts:450` (`v2CallService.sweepIdleCalls`) | Retention sweep: v2 calls with no activity this long are archived. Live exchanges touch `lastActivityAt` and are never swept. |
| `V2_SWEEP_INTERVAL_MS` / `sweepIntervalMs` | `300000` (5 min) | `common/config.ts:111`, applied at `index.ts:449-452` | How often v2 `sweepIdleCalls` runs. |
| `V2_IDEMPOTENCY_TTL_MS` / `idempotencyTtlMs` | `86400000` (24h) | `common/config.ts:106` | How long a stored idempotency response is replayed. |
| `V2_IDEMPOTENCY_SWEEP_INTERVAL_MS` / `idempotencySweepIntervalMs` | `600000` (10 min) | `common/config.ts:115`, applied at `index.ts:438-443` | How often the idempotency TTL sweep runs. |

### 4.2 VoiceBridge call lifecycle (backend)

| Constant | Value | File:line | Meaning |
|---|---|---|---|
| `CALL_RING_TTL_MS` | `180000` (3 min) | `voicebridge/service.ts:55` | `call_incoming` payload `expiresAt` = `anchor + 3 min` (`service.ts:182`). `attemptRing` checks `now >= anchor + CALL_RING_TTL_MS` → gives up (`service.ts:249-254`). |
| `QUEUED_NOTIFICATION_TTL_MS` | `120000` (2 min) | `voicebridge/service.ts:48` | Notifications queued while phone offline are only flushed while fresh (`service.ts:227-231`, `1019-1028`). `registerPhone` flush also drops stale entries older than this (`service.ts:970-974`). |
| `PENDING_CALL_TTL_MS` | `180000` (3 min) | `voicebridge/service.ts:64` | Hard ceiling for unanswered `pending` sessions, anchored at `resumedAt ?? createdAt` (`service.ts:896`, `getUserActiveCall:521-529`, `sweepStaleSessions:884-905`). After this, `cancelCall(..., asExpired=true)` → `call_expired` event. |
| `STALE_ACTIVE_THRESHOLD_MS` | `1800000` (30 min) | `voicebridge/service.ts:38` | Active sessions with no `lastActivityAt` older than this are auto-`completeCall`ed by `sweepStaleSessions` (`service.ts:907-919`). |
| `COMPLETED_RETENTION_MS` | `3600000` (60 min) | `voicebridge/service.ts:36` | `completed` sessions get `retentionExpiresAt = now + 60 min` (`service.ts:690`), then deleted by `SessionSweeper`. |
| `CANCELLED_RETENTION_MS` | `300000` (5 min) | `voicebridge/service.ts:37` | `cancelled`/`aborted` sessions get `retentionExpiresAt = now + 5 min` (`service.ts:748, 792`), then deleted. |
| `MAX_RING_RETRIES` + `RING_RETRY_INTERVAL_MS` | `12 × 15000` (12 retries, 15s apart = 3 min total) | `voicebridge/service.ts:73-74` | Ring-gate retry budget when agent offline/busy (`service.ts:240-317`). Matches the 3-min ring TTL. |
| `SIGNALING_HEARTBEAT_MS` / `heartbeatMs` | `60000` (60s) | `common/config.ts:42`, `signaling/server.ts:79, 166-177` | Server→phone `ws.ping()` interval; missed pong → `ws.terminate()`. Matches client's `OkHttp pingInterval 60s` (`SignalingClient.kt:57`). |
| `ONLINE_WINDOW_MS` | `300000` (5 min) | `voicebridge/ai-keys.ts:44` | AI key considered online if `last_used_at` within 5 min (`ai-keys.ts:229-248`, `isAiKeyOnlineByName:257-263`). |
| Periodic `sweepStaleSessions` interval | `300000` (5 min) | `backend/src/index.ts:325-328` + `service.ts:882-926` | Backstop for orphaned sessions (phone offline during decline, etc.). |
| `SessionSweeper` interval | `300000` (5 min) | `backend/src/index.ts:339-346` (`intervalMs: 5*60*1000`), sweeper at `voicebridge/sweeper.ts:20` | Deletes `retentionExpiresAt`-expired sessions (calls `isExpired`, `voicebridge/service.ts:1043-1047`). |
| `AGENT_STATUS_TTL_MS` (phone) | `30000` (30s) | `mobile/.../home/HomeViewModel.kt:296` | TTL gate for per-agent `GET /agents/:id/status` — both `Connected` handler and `profiles` collector coalesce within this window (`HomeViewModel.kt:271-293`). |

### 4.3 Mobile fallback & poll cadences

| Constant | Value | File:line | Meaning |
|---|---|---|---|
| `FallbackPollCadence.ACTIVE_MS` | `10000` (10s) | `mobile/.../call/FallbackPollCadence.kt:23` | Foreground/ring-in-flight poll cadence (`SignalingForegroundService` fallback poll, `SignalingForegroundService.kt:370-468` gated by `shouldRunFallbackPoll`). |
| `FallbackPollCadence.BACKGROUND_MS` | `60000` (60s) | `FallbackPollCadence.kt:24` | Recent background, streak ≤ 20. |
| `FallbackPollCadence.IDLE_MS` | `300000` (5 min) | `FallbackPollCadence.kt:25` | Deep background/Doze idle. |
| `FallbackPollCadence.ACTIVE_WINDOW_MS` | `300000` (5 min) | `FallbackPollCadence.kt:26` | Time since `noteActivity()` within which poll stays at 10s. |
| `FallbackPollCadence.DOZE_SKIP_MS` + `DOZE_EVERY` | `300000` (5 min), every 3rd tick | `FallbackPollCadence.kt:27-28` | In `PowerManager.isDeviceIdleMode`, skips 2 of 3 ticks (`FallbackPollCadence.kt:78-80`). |
| `FallbackPollCadence.MAX_BACKGROUND_STREAK` | `20` | `FallbackPollCadence.kt:29` | After 20 background ticks, drops to `IDLE_MS`. |
| `AvailabilityPollCadence.CONNECTED_MS` | `60000` (60s) | `mobile/.../home/AvailabilityPollCadence.kt:23` | Steady-state foreground AI-chip refresh (`HomeViewModel.kt:165` via `computeDelayMs`). |
| `AvailabilityPollCadence.FAILURE_BACKOFF_MS` | `[60000, 120000, 240000, 480000]` | `AvailabilityPollCadence.kt:30-35` | Exponential ladder on consecutive `listAiKeys` failures (`HomeViewModel.kt:165`). |
| `RING_TIMEOUT_MS` (phone) | `60000` (60s) | `mobile/.../call/SignalingForegroundService.kt:641` | Auto-decline timer for a ringing call (`SignalingForegroundService.kt:278-303`). |
| `PUSH_IDLE_STOP_MS` | `20000` (20s) | `SignalingForegroundService.kt:644` | Idle-park backstop after a push that never produced a ring (`SignalingForegroundService.kt:523-530`). |
| `RECENT_RING_TTL_MS` + `MAX_RECENT_RINGS` | `300000` (5 min), 16 | `SignalingForegroundService.kt:645-646` | Dedupe ring deliveries (`SignalingForegroundService.kt:197-203`). |
| `FCM_REGISTER_DEBOUNCE_MS` | `60000` (60s) | `SignalingForegroundService.kt:656` | Debounce FCM token reconciliation on WS flaps (`SignalingForegroundService.kt:110-116`). |
| `CallViewModel` transcript fallback poll | `4000` (4s) gate, throttled to `20000` (20s) terminal check | `mobile/.../call/CallViewModel.kt:459, 471` | `maybePollTranscript()` — polls `GET /calls/:id/transcript` when `signalingClient.connectionState != CONNECTED` and phase ACTIVE/RECONNECTING; also checks terminal status every 20s to tear down wedged calls. |
| `CallService` call watchdog | `effectiveMaxCallMs() + WAKELOCK_TIMEOUT_BUFFER_MS` | `CallService.kt:61-65, 263-275` | `callWatchdogJob` force-ends wedged calls at max-call duration (debug override via intent extra). |

### 4.4 Other infra timeouts

| Constant | Value | File:line |
|---|---|---|
| `DB_POOL_ACQUIRE_TIMEOUT` / `poolAcquireTimeoutMs` | `10000` (10s) | `common/config.ts:25` |
| `DB_POOL_IDLE_TIMEOUT` / `poolIdleTimeoutMs` | `30000` (30s) | `common/config.ts:26` |
| `DB_VERIFICATION_INTERVAL_MS` | `0` (disabled by default) | `common/config.ts:27` |
| DB `statement_timeout` per connection | `5s` | `backend/src/index.ts:134, 157, 384` (`SET statement_timeout = '5s'` on pool connect) |
| `V2_RECOVERY_SLOW_MS` / `recoverySlowMs` | `30000` (30s) | `common/config.ts:128` — boot recovery slower than this logs a warning (`v2/call-service.ts:815-821`). |
| `V2_SSE_REPLAY_MAX_EVENTS` | `500` | `common/config.ts:124` |
| `V2_IDEMPOTENCY_MAX_ENTRIES` (memory) | `50000` | `common/config.ts:120` |
| Render health keep-warm | `5 min` cron | `.github/workflows/keep-render-warm.yml:23-24` |
| Signaling `MAX_RECONNECT_ATTEMPTS` / `MAX_SHORT_LIVED_STREAK` | `20` attempts, `4` short-lived sockets (<60s) → park | `mobile/.../call/SignalingClient.kt:107-112` |
| Signaling backoff | `MIN_RECONNECT_DELAY_MS 2000`, exponential `* 2^(attempt-1)` capped at `MAX_BACKOFF_MS 300000` (5 min) | `SignalingClient.kt:312-318` |

---

## 5. CONCURRENCY REALITY CHECK

### 5.1 What the task asks

> Does the OpenCode harness support any form of background/async tool execution, or is it strictly sequential (one tool call in flight at a time, agent fully blocked until it returns)?

### 5.2 Evidence from the codebase vs harness side

**This question cannot be answered from the AgentCall codebase alone** — the harness is not part of the repo. The following is what was verifiable:

* **No `opencode.json` harness config** exists at the workspace root (checked via `bash: Get-Content opencode.json` → no output). The only `.opencode/` directory in the workspace is the embedded `impeccable` skill library and its vendored `zod` copy (`backend/src/mcp/...` is the MCP surface, not a harness config).
* **The MCP `STREAMABLE HTTP` transport is per-request blocking by design** — each `CallToolRequestSchema` handler (`backend/src/mcp/endpoint.ts:95-112`) is `async` and returns only when the handler resolves. The SDK's `StreamableHTTPServerTransport` (`endpoint.ts:114-116`) holds the HTTP response until the handler's promise settles.
* **`send_message_and_wait` explicitly assumes sequential blocking** — it is documented as `combines send_message + get_transcript polling into one round trip` and its implementation holds the single tool-return promise open for up to 45s (or 5 min in v2) (`mcp/tools.ts:302-347`). The `aiWaitLease` mechanism (`voicebridge/service.ts:366-415`) is a *single waiter per callId* — a second concurrent `send_message_and_wait` for the same `callId` ref-counts the lease but still blocks its own tool return independently. There is no out-of-band "agent is still alive" heartbeat while the tool is blocked except the MCP-level `notifications/ping` (which is a separate JSON-RPC notification, not a tool return).
* **Observable execution constraint in this audit session:** the tool runner enforces `You can only invoke one tool call in a single message. To invoke multiple tools in parallel, emit them across separate messages in the same assistant turn, one per message.` This is the harness's own admission of its execution model — sequential per-call, with at most batched sequential calls per turn, not true background/async execution. No `background: true` or task-queue primitive was surfaced in any skill doc checked (including `agent-harness-construction` at `C:\Users\91808\.opencode\skills\agent-harness-construction\SKILL.md:1-74`, which discusses ReAct vs function-calling but not OpenCode's concurrency primitive).

### 5.3 Verdict (with explicit uncertainty)

* **From the AgentCall codebase:** all server-side primitives (session watcher, `aiWaitLeases`, `CallEventBus`) are built for a **single blocked tool call** to hold the response. An "agent signals it's still alive while working" design **cannot be achieved by overlapping two MCP tool calls** if the harness truly blocks on the first — the agent would need to either (a) not block (use `send_message` + `get_transcript` polling with short waits) or (b) send MCP `notifications/ping` on a separate transport while the tool call is in flight.
* **From the harness side:** the definitive answer requires OpenCode's own execution-model docs (`opencode.ai/docs` or the harness runtime source). **Not verified in this session** — `WebFetch` to `opencode.ai/docs` was not performed (would have been external network, not local evidence). The harness constraint message observed in this session suggests **strictly sequential** (one tool call in flight at a time, no background), but this should be confirmed against the live OpenCode harness version before relying on it for a redesign.

**Recommendation before redesign:** run `opencode --help` / check `https://opencode.ai/docs` for `background` / `concurrency` / `notifications/ping` support, or test empirically: start a `send_message_and_wait` with `timeout_seconds: 45` and attempt a second tool call while it is in flight — observe whether the harness queues, rejects, or interleaves it.

---

## 6. COST / INFRA CONSTRAINTS

### 6.1 Current hosting confirmed

* **`render.yaml:8` → `plan: free`**, `region: singapore`, `healthCheckPath: /api/v1/health`, Docker deploy (`render.yaml:1-21`). No paid plan flag, no `PERSISTENCE_MODE=v2` — `render.yaml:17` sets `PERSISTENCE_MODE=database` (v1 Postgres via `DATABASE_URL`).
* **Database:** Neon free tier implied (Postgres `DATABASE_URL` via `render.yaml:14-15` env sync, `backend/src/index.ts:120-145` pool config). Not explicitly named in `render.yaml` but matches `docs/FREE_ARCHITECTURE.md` and `backend/.env.example`.
* **Code confirms free-tier awareness:** `keep-render-warm.yml:1-18` header block documents Render free-tier spin-down after ~15 min without traffic, and tightens the ping to 5 min to survive jitter. `PERSISTENCE_MODE` defaults to `dual-write` locally (`common/config.ts:28`), but production uses `database` per `render.yaml:17`.

### 6.2 Render free-tier idle-kill behavior (ground truth as of today)

| Behavior | Evidence |
|---|---|
| Spin-down after ~15 min without inbound HTTP/WebSocket traffic | Documented at `.github/workflows/keep-render-warm.yml:2-5` (links `https://docs.render.com/free#spinning-down-on-idle`), also `docs/FREE_ARCHITECTURE.md`. |
| Cold-start latency ~20–30s on next request | Same file header (`:4-5`). Mitigated by GitHub Actions cron every 5 min (`:23-24`). |
| **Does NOT kill FCM deliveries** — post-`2414f37`, rings ride FCM, not WS | `2414f37` diff shows `notifyPhone` first tries FCM regardless of WS state (`backend/src/voicebridge/service.ts:999-1001`). The 09-08 audit live-verified rings delivered with **no WS at all** (FGS stopped / process dead, `AUDIT_SYSTEM_GROUND_TRUTH_2026-09-08.md:54-58`). So Render killing an idle WS does not lose rings. |
| **Can kill mid-call WS and in-flight `send_message_and_wait` HTTP** | `AUDIT_SYSTEM_GROUND_TRUTH_2026-09-08.md:101` residual exposure still applies. The in-call WS (`SignalingClient.kt:56-112`) will be `terminate()`d and must reconnect (up to 5 min backoff). A `send_message_and_wait` HTTP request held open for > Render's request timeout will be cut — agent sees a transport error, not a tool return. |
| Keep-warm pings use `GET /health` (no auth, no DB, 20 req/10s limit, `backend/src/routes.ts:141-147`) and fallback `/api/v2/health` | `keep-render-warm.yml:33-48` — 30s curl timeout, warns but does not fail on 200 after cold-start. |

### 6.3 Neon free-tier implications

* **Connection count:** Pool `min 2, max 10` (`common/config.ts:23-24`, `backend/src/index.ts:124-127` and `376-379` for v2 pool). Neon free tier allows ~100 connections (varies by plan) — 10 + 10 = 20 max from the backend alone is safe. Adding a poll-heavy mailbox design (many `GET /calls/:id` or `GET /transcript` per agent) adds query load but not connections if pools are not expanded.
* **Idle timeouts:** `idleTimeoutMillis 30000` (`common/config.ts:26`), `statement_timeout 5s` per connection (`backend/src/index.ts:134, 384`). The keep-warm ping every 5 min touches HTTP, not necessarily PG — `DatabaseHealthMonitor` is the only periodic PG toucher (`common/config.ts:27` default 0 = disabled). If no traffic beyond keep-warm, PG connections idle for 30s then evicted, then re-established on next call — cold PG adds latency but no data loss.
* **Storage:** Neon free tier ~0.5–3 GB + row/BLOB limits. Call sessions (`VoiceCallSession` JSONB) plus idempotency table (`v2_idempotency`) are the growth drivers. `V2_IDEMPOTENCY_TTL_MS 24h` + 10-min sweep (`common/config.ts:106,115`) and `V2_IDEMPOTENCY_MAX_ENTRIES 50000` (memory) / TTL-bounded Postgres table keep the table bounded. But a long-lived async mailbox where many agents leave calls `pending`/`active` for hours would accumulate rows that `sweepStaleSessions` (30-min active, 3-min pending) and `SessionSweeper` (60-min completed retention) would otherwise delete — so retention policy matters more than poll cadence.

### 6.4 Does an async mailbox design (session persisted, agent polls on own schedule, minutes-long sessions) fit zero-cost?

**Fits, with three flagged pressure points:**

1. **Render request timeout vs long polls.** A mailbox where the agent `GET /calls/:id/events` (SSE) or `POST /calls/:id/messages` and then `GET /transcript` on a lazy schedule is **HTTP-short per request** — each poll is a fast REST call, not a held `send_message_and_wait`. This **fits** free-tier: no held request to be killed. But if the design keeps `send_message_and_wait` with `timeout_seconds: 86400` (24h, v2 lease) held open as a long poll, Render will kill the HTTP connection well before it completes (Render's proxy times out at ~100s idle / 30 min total; not documented in-repo but flagged as `transport error` in `backend/src/voicebridge/fcm.ts:156-160` pattern). So the long-wait variant **does not fit** without switching to short polls or SSE reconnect (v2's `GET /calls/:id/events` with `?after=` replay, `backend/src/v2/routes.ts`).

2. **Neon connection/row growth under minutes-long sessions.** If sessions stay `active` for hours (user leaves call open), `lastActivityAt` keeps getting touched only by real messages (`service.ts:596`), not by polls — so `sweepStaleSessions` (30-min) **will** auto-complete a truly idle active call (`service.ts:907-919`). An async mailbox that intentionally keeps calls alive for hours would need to either (a) send periodic heartbeat messages (counts as activity, resets the 30-min clock) or (b) raise `STALE_ACTIVE_THRESHOLD_MS`. Both increase storage and keep connections warm — still within free-tier row count if archived promptly (`V2_CALL_IDLE_ARCHIVE_MS 24h` default, `common/config.ts:110`), but a burst of concurrent long sessions could push toward Neon's row/storage soft limit. Not a hard tier boundary, but must be load-tested.

3. **Render cold starts vs push latency.** With no held connection, FCM is the only wake path for idle phones (verified). That's already the FCM-primary design and is free. A mailbox that relies on **agent-initiated polling** (agent GETs transcript every 30s) does not need the phone to be awake — so cold starts only affect the agent's poll latency (~20–30s first request), which the keep-warm cron already mitigates. No paid tier needed if the 5-min keep-warm stays.

**Verdict:** A short-poll mailbox (agent polls `get_transcript`/`getCall` every 15–60s, calls live minutes) **fits** Render Free + Neon Free without forcing a paid tier, provided (i) no HTTP request is held for > ~30s (avoid long `send_message_and_wait` waits in production; use `send_message` + short-poll `get_transcript`), (ii) the 30-min stale-active sweep is either embraced (calls auto-close at 30 min idle) or explicitly opted-out per-call, and (iii) the v2 event log's per-call replay cap (`V2_SSE_REPLAY_MAX_EVENTS 500`, `common/config.ts:124`) is respected. The long-wait `send_message_and_wait` variant (v2 lease with no timeout) **does push against** Render's free request-timeout limit and would need either SSE reconnect or a paid service with no request timeout.

---

## 7. APPENDIX — Key file map (every citation reachable)

```
backend/src/mcp/tools.ts              — tool definitions + send_message_and_wait wait loop
backend/src/mcp/endpoint.ts           — MCP Streamable HTTP transport, session lifecycle, heartbeat vs touch
backend/src/mcp/session-registry.ts   — session registry, sweepDead/sweepExpired, liveness vs idle
backend/src/mcp/identity.ts           — AsyncLocalStorage identity, DEFAULT_AGENT_NAME fallback
backend/src/common/config.ts          — all timeout constants + v2 flags
backend/src/signaling/server.ts       — WS server /phone, heartbeat 60s, rate limits
backend/src/voicebridge/service.ts    — VoiceBridgeService, aiWaitLeases, pendingNotifications, ring gate, sweeps
backend/src/voicebridge/ai-keys.ts    — ONLINE_WINDOW_MS 5 min, isAiKeyOnlineByName
backend/src/voicebridge/fcm.ts        — FCM HTTP v1 send, android high priority
backend/src/index.ts                  — startup recovery, cleanup scheduler, sweeper wiring, MCP registration
backend/src/routes.ts                 — REST routes, ownership checks, GET /health
backend/src/v2/call-service.ts        — v2 engine: streaming TTS, turn leases, silence policy, recovery
render.yaml                           — Render free plan, region, healthCheckPath, PERSISTENCE_MODE=database
.github/workflows/keep-render-warm.yml— 5-min health ping, Render idle docs
MCP_API_SPEC.md                       — spec snapshot verified against endpoint.ts/tools.ts
docs/AUDIT_SYSTEM_GROUND_TRUTH_2026-09-08.md — prior FCM-primary verification (commit 2414f37)

mobile/android/app/src/main/java/com/agentcall/app/call/
  SignalingClient.kt                  — OkHttp WS, pingInterval 60s, reconnect/backoff, park/connectIfIdle
  SignalingForegroundService.kt       — ring validation, RING_TIMEOUT_MS 60s, fallback poll gating, maybeParkAndStop
  AgentCallMessagingService.kt        — FCM onMessageReceived → Ring-from-push
  FallbackPollCadence.kt              — 10s/60s/5min adaptive poll tiers, pure function
  CallService.kt                      — active call: WS for duration, TTS, watchdog, transcript handling
  CallViewModel.kt                    — in-call FSM, aiResponding/agentOnline banner, transcript fallback poll
  CallState.kt                        — CallStateHolder (single ring truth)
mobile/android/app/src/main/java/com/agentcall/app/home/
  HomeViewModel.kt                    — health-based Ready/Offline, availability poll, agent chips
  AvailabilityPollCadence.kt          — 60s connected, doubling failure ladder
mobile/android/app/src/main/java/com/agentcall/app/data/api/ApiService.kt — Retrofit endpoints
```

---

*Report produced without code edits. All code excerpts were read in full (not from search snippets) and line numbers correspond to the working tree at the time of this audit (`cf44131` HEAD). Predictions marked as projected were not live-measured; reconciliation with the 09-08 audit was verified against `git log` and live code, not memory.*
